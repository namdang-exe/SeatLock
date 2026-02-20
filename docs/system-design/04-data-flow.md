# SeatLock — Section 4: Data Flow

> **Phase 0 — Design Only**
> Generated after Section 4 design interview.
> All flows reference the Postgres table DDL and Redis schema defined below.

---

## Table of Contents

1. [Postgres Table Schemas](#1-postgres-table-schemas)
2. [Redis Schema](#2-redis-schema)
3. [Flow 1 — Hold Creation](#3-flow-1--hold-creation)
4. [Flow 2 — Booking Confirmation](#4-flow-2--booking-confirmation)
5. [Flow 3 — Hold Expiry](#5-flow-3--hold-expiry)
6. [Flow 4 — Cancellation](#6-flow-4--cancellation)
7. [Flow 5 — Availability Query](#7-flow-5--availability-query)
8. [Cross-Cutting Decisions](#8-cross-cutting-decisions)

---

## 1. Postgres Table Schemas

### `users`

```sql
CREATE TABLE users (
    user_id       UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    email         VARCHAR(255)  NOT NULL UNIQUE,
    password_hash VARCHAR(255)  NOT NULL,
    phone         VARCHAR(20),
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_users_email ON users (email);
```

### `venues`

```sql
CREATE TABLE venues (
    venue_id   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(255) NOT NULL,
    address    VARCHAR(500) NOT NULL,
    city       VARCHAR(100) NOT NULL,
    state      VARCHAR(50)  NOT NULL,
    status     VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE'
                            CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_venues_status ON venues (status);
```

### `slots`

```sql
CREATE TABLE slots (
    slot_id    UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    venue_id   UUID        NOT NULL REFERENCES venues (venue_id),
    start_time TIMESTAMPTZ NOT NULL,
    status     VARCHAR(10) NOT NULL DEFAULT 'AVAILABLE'
                           CHECK (status IN ('AVAILABLE', 'HELD', 'BOOKED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Primary browse pattern: all slots for a venue on a given day
CREATE INDEX idx_slots_venue_start ON slots (venue_id, start_time);

-- Availability filter: find AVAILABLE slots for a venue
CREATE INDEX idx_slots_venue_status ON slots (venue_id, status);
```

> **Note:** No `end_time` column. End time is derived in application code as
> `start_time + interval '60 minutes'` from the system constant
> `seatlock.slot.duration-minutes=60`. This avoids storing redundant data and
> means slot duration can be changed via config without a schema migration.

### `holds`

```sql
CREATE TABLE holds (
    hold_id    UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID        NOT NULL,
    user_id    UUID        NOT NULL REFERENCES users (user_id),
    slot_id    UUID        NOT NULL REFERENCES slots (slot_id),
    expires_at TIMESTAMPTZ NOT NULL,
    status     VARCHAR(10) NOT NULL DEFAULT 'ACTIVE'
                           CHECK (status IN ('ACTIVE', 'CONFIRMED', 'EXPIRED', 'RELEASED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_holds_session_slot UNIQUE (session_id, slot_id)
);

-- Hold lookup by session (confirm flow, expiry flow)
CREATE INDEX idx_holds_session ON holds (session_id);

-- Hold lookup by slot (admin, audit)
CREATE INDEX idx_holds_slot ON holds (slot_id);

-- Expiry polling job: find all ACTIVE holds past their TTL
CREATE INDEX idx_holds_expiry ON holds (expires_at, status);
```

> **UNIQUE (session_id, slot_id):** Belt-and-suspenders against retry storms.
> Redis SETNX is the primary concurrency gate; this constraint is the
> Postgres-level safety net ensuring no duplicate hold rows even if a request
> is replayed.

### `bookings`

```sql
CREATE TABLE bookings (
    booking_id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id          UUID        NOT NULL,
    confirmation_number VARCHAR(20) NOT NULL,
    user_id             UUID        NOT NULL REFERENCES users (user_id),
    slot_id             UUID        NOT NULL REFERENCES slots (slot_id),
    hold_id             UUID        NOT NULL REFERENCES holds (hold_id),
    status              VARCHAR(10) NOT NULL DEFAULT 'CONFIRMED'
                                    CHECK (status IN ('CONFIRMED', 'CANCELLED')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    cancelled_at        TIMESTAMPTZ,

    CONSTRAINT uq_bookings_session_slot UNIQUE (session_id, slot_id)
);

-- Booking history for a user (GET /bookings)
CREATE INDEX idx_bookings_user ON bookings (user_id);

-- Cancel + receipt lookup (POST /bookings/{confirmationNumber}/cancel)
CREATE INDEX idx_bookings_confirmation ON bookings (confirmation_number);

-- Internal session grouping (confirm + cancel flows)
CREATE INDEX idx_bookings_session ON bookings (session_id);

-- Admin venue view (GET /admin/venues/{venueId}/bookings via join to slots)
CREATE INDEX idx_bookings_slot ON bookings (slot_id);
```

---

## 2. Redis Schema

| Key | Value (JSON) | TTL | Set via |
|-----|-------------|-----|---------|
| `hold:{slotId}` | `{ "holdId": "...", "userId": "...", "sessionId": "...", "expiresAt": "..." }` | 1800s (30 min) | `SET hold:{slotId} {json} NX EX 1800` |
| `slots:{venueId}:{date}` | JSON array of `[{ "slotId": "...", "startTime": "...", "status": "..." }, ...]` | 5s | `SET slots:{venueId}:{date} {json} EX 5` — `{date}` is ISO 8601 (e.g. `2025-08-01`) |

**Key design choices:**

- `hold:{slotId}` uses `NX` (only set if not exists) — this is the atomic
  concurrency gate that prevents double-booking.
- `slots:{venueId}:{date}` is a short-TTL read cache. 5s staleness is
  acceptable by design (OQ-10 resolved). A stale cache hit returning a slot as
  AVAILABLE when it is actually HELD results in a `409 SLOT_NOT_AVAILABLE` at
  hold time, not a double-booking.
- Both keys are explicitly `DEL`-ed on writes (hold, confirm, cancel) in
  addition to relying on TTL expiry.

---

## 3. Flow 1 — Hold Creation

**Endpoint:** `POST /api/v1/holds`
**Actor:** Authenticated user
**Owned by:** booking-service

### Steps

```
1. VALIDATE INPUT
   - Reject if Idempotency-Key header is absent → 400 BAD_REQUEST
   - Reject if slotIds array is empty or contains invalid UUIDs → 400 BAD_REQUEST

2. VERIFY SLOTS EXIST (Postgres read)
   - SELECT slot_id FROM slots WHERE slot_id IN (:slotIds)
   - If any slotId not found → 404 NOT_FOUND
   - (No status check here — status is enforced by Redis SETNX in step 4)

3. GENERATE IDs (or DEDUPLICATE)
   - sessionId = Idempotency-Key header value (required; validated in step 1)
       SELECT * FROM holds WHERE session_id = :sessionId AND status = 'ACTIVE'
       If holds found → return 200 with existing hold data (idempotent replay; skip remaining steps)
   - holdId    = UUID v4 per slot
   - expiresAt = now() + 30 minutes

4. REDIS SETNX PHASE (all-or-nothing)
   For each slotId:
     SET hold:{slotId} {"holdId":…,"userId":…,"sessionId":…,"expiresAt":…} NX EX 1800
   If ANY command returns nil (key already exists):
     DEL hold:{slotId} for every key successfully set in THIS request
     → 409 SLOT_NOT_AVAILABLE

5. POSTGRES TRANSACTION
   BEGIN;
     INSERT INTO holds (hold_id, session_id, user_id, slot_id, expires_at, status)
       VALUES … (one row per slot, status='ACTIVE');
     UPDATE slots SET status = 'HELD'
       WHERE slot_id IN (:slotIds) AND status = 'AVAILABLE';
     -- Assert updated row count = len(slotIds)
     -- Mismatch: at least one slot is HELD/BOOKED in Postgres while its Redis key
     -- had already expired (within the ≤60s @Scheduled lag window)
   COMMIT;
   If updated row count < len(slotIds):
     DEL hold:{slotId} for each slot in request
     → 409 SLOT_NOT_AVAILABLE
   If transaction fails for other reason:
     DEL hold:{slotId} for each slot in request
     → 500 INTERNAL_SERVER_ERROR
     (Self-heals via Redis TTL if DEL also fails — slot will auto-release in ≤30 min)

6. INVALIDATE AVAILABILITY CACHE
   DEL slots:{venueId}:{date}

7. RETURN 200
   { sessionId, expiresAt, holds: [{ holdId, slotId, startTime }, …] }
```

### Failure Paths

| Failure | Behaviour |
|---------|-----------|
| Empty / invalid slotIds | 400 — before any I/O |
| Any slotId not in Postgres | 404 — before any Redis/Postgres writes |
| Any SETNX returns nil (slot already held/booked) | DEL all keys set in this request → 409 `SLOT_NOT_AVAILABLE` |
| Slot HELD/BOOKED in Postgres but SETNX succeeded (≤60s expiry lag window) | DEL Redis keys → 409 `SLOT_NOT_AVAILABLE` |
| Postgres INSERT/UPDATE fails after Redis success | DEL Redis keys → 500; if DEL fails, keys expire within 30 min |
| booking-service crashes mid-flight | Redis TTL self-heals; Postgres transaction rolls back automatically |

### Decisions

- **Redis SETNX is the concurrency gate, not `SELECT FOR UPDATE`** — SETNX is
  sub-millisecond and gives us per-slot TTL natively. A Postgres advisory lock
  or `FOR UPDATE` would require a round-trip and hold a row lock for the
  duration of the Redis call.
- **`slot.status` updated to `HELD` in Postgres at hold time** — availability
  queries that miss the Redis cache will read the correct status from Postgres.
- **`UNIQUE (session_id, slot_id)` on `holds`** — prevents duplicate rows from
  retry storms even if the Redis gate is bypassed.
- **`Idempotency-Key` header is required on `POST /holds`** — the header value
  is used as `sessionId` for the request. On a duplicate request with the same
  key, existing ACTIVE holds are returned directly without re-executing hold
  logic (idempotent replay). The `UNIQUE (session_id, slot_id)` constraint is
  the Postgres-level safety net if the idempotent check races a concurrent
  duplicate.
- **`AND status = 'AVAILABLE'` guard on the Postgres UPDATE (step 5)** —
  secondary safety net for the ≤60s @Scheduled expiry lag window: a Redis TTL
  may have fired (key gone, SETNX succeeds) while the slot's Postgres status is
  still `HELD`. Row-count assertion catches this and returns 409, not 500.

---

## 4. Flow 2 — Booking Confirmation

**Endpoint:** `POST /api/v1/bookings`
**Actor:** Authenticated user
**Owned by:** booking-service

### Steps

```
0. IDEMPOTENCY CHECK (Postgres read, uses idx_bookings_session)
   SELECT * FROM bookings WHERE session_id = :sessionId AND status = 'CONFIRMED'
   If bookings found → return 201 with existing booking data (idempotent replay; skip remaining steps)

1. LOAD ACTIVE HOLDS (Postgres read)
   SELECT * FROM holds
   WHERE session_id = :sessionId AND status = 'ACTIVE'
   If none → 404 NOT_FOUND (session unknown or already confirmed/expired)

2. AUTHORIZE
   For each hold: assert hold.user_id = JWT userId → 403 FORBIDDEN if mismatch

3. VERIFY REDIS KEYS (one per slot)
   For each hold:
     GET hold:{slotId}
     Assert key exists AND parsed holdId = hold.hold_id
   If any key missing → 409 HOLD_EXPIRED
   If key exists but holdId ≠ hold.hold_id → 409 HOLD_MISMATCH
     ("Hold state mismatch. Refresh availability and create a new hold.")
   (No Postgres writes occur if either check fails)

4. GENERATE CONFIRMATION NUMBER
   confirmationNumber = "SL-" + YYYYMMDD + "-" + 4-digit-random
   (Same value written to every Booking row in this session)

5. POSTGRES TRANSACTION
   BEGIN;
     INSERT INTO bookings
       (booking_id, session_id, confirmation_number, user_id,
        slot_id, hold_id, status, created_at)
       VALUES … (one row per slot, status='CONFIRMED');
     UPDATE holds SET status = 'CONFIRMED'
       WHERE session_id = :sessionId;
     UPDATE slots SET status = 'BOOKED'
       WHERE slot_id IN (:slotIds);
   COMMIT;
   If transaction fails → rollback; Redis holds expire naturally → 500

6. CLEAN UP REDIS
   For each slotId: DEL hold:{slotId}
   DEL slots:{venueId}:{date}
   (DEL failures are non-fatal; keys expire within 30 min; slot is already
    BOOKED in Postgres so no double-booking is possible)

7. RETURN 201
   { confirmationNumber, sessionId,
     bookings: [{ bookingId, slotId, startTime, endTime }, …] }
```

### Failure Paths

| Failure | Behaviour |
|---------|-----------|
| Idempotency-Key matches existing CONFIRMED booking | 201 with existing data (idempotent replay) |
| No active holds for sessionId | 404 |
| hold.user_id ≠ JWT userId | 403 `FORBIDDEN` |
| Redis key missing (hold expired) | 409 `HOLD_EXPIRED`; no Postgres writes |
| Redis key exists but holdId mismatch | 409 `HOLD_MISMATCH`; no Postgres writes |
| Postgres transaction fails | rollback; Redis holds expire naturally; 500 |
| Redis DEL fails after Postgres commit | stale keys expire ≤30 min; slot already `BOOKED` — no double-booking possible |

### Decisions

- **Redis GET checked before any Postgres write** — the hold expiry check is the
  last safety gate before committing booking records. This ordering ensures
  expired holds never produce booking rows.
- **All three Postgres writes in one transaction** (INSERT bookings + UPDATE
  holds + UPDATE slots) — partial commit is not possible; if any fails, all
  roll back.
- **One `confirmationNumber` per session** — the same string is written to every
  Booking row so that `GET /bookings/{confirmationNumber}` and
  `POST /bookings/{confirmationNumber}/cancel` can find all sibling records with
  a single indexed lookup.

---

## 5. Flow 3 — Hold Expiry

**Mechanism:** Spring `@Scheduled` job inside booking-service, runs every 60 seconds.
**Not a Redis keyspace notification listener** — see decision note below.

### Steps

```
1. POLLING QUERY (Postgres, uses idx_holds_expiry — FOR UPDATE SKIP LOCKED)
   SELECT hold_id, slot_id, session_id FROM holds
   WHERE expires_at < now() AND status = 'ACTIVE'
   ORDER BY expires_at ASC
   LIMIT 500
   FOR UPDATE SKIP LOCKED
   (SKIP LOCKED: each booking-service instance locks a non-overlapping set of rows;
    rows locked by another instance are silently skipped and picked up in the next cycle)

2. BATCH TRANSACTION (one transaction for the entire batch of ≤500 holds)
   BEGIN;
     UPDATE holds SET status = 'EXPIRED'
       WHERE hold_id IN (:holdIds) AND status = 'ACTIVE';
       -- AND status='ACTIVE' is belt-and-suspenders: guards against any concurrent
       -- instance that might have processed a row before SKIP LOCKED was in effect
     UPDATE slots SET status = 'AVAILABLE'
       WHERE slot_id IN (:slotIds) AND status = 'HELD';
       -- Conditional and non-fatal: slot may already be AVAILABLE (e.g. cancelled
       -- between hold creation and expiry); mismatch is silently tolerated
   COMMIT;
   On deadlock or timeout:
     Retry batch up to 3 times with exponential backoff
     If retries exhausted: halve batch size and retry once more
     Holds not processed this cycle are picked up in the next 60s poll

3. REDIS
   No Redis operation needed — the TTL has already fired and the key is gone.
   (The job does NOT attempt DEL; deleting a non-existent key is a no-op anyway)

4. NOTIFICATION (async, out of band)
   For each expired hold: publish "hold expired" event → notification-service
   (notification-service is async — job does not wait for delivery)
```

### Why Polling, Not Redis Keyspace Notifications

| Factor | Polling (@Scheduled) | Keyspace Notifications |
|--------|----------------------|------------------------|
| ElastiCache config | No change required | Requires `notify-keyspace-events` enabled — not on by default |
| Lag | ≤60s | Near-instant |
| Acceptability | Yes — booking path is guarded by Redis GET at confirm time | Would shorten lag but adds operational complexity |
| Testability | Simple unit + integration tests | Requires Redis listener, harder to test in isolation |
| Replay on crash | Easy — re-query Postgres on restart | Events lost if listener is down |

**Decision:** 60s polling lag is acceptable because the booking confirmation
path independently checks Redis GET and rejects expired holds with 409. The 60s
window does not open a double-booking window — it only determines how quickly a
slot appears as AVAILABLE again to other users browsing.

---

## 6. Flow 4 — Cancellation

**Endpoint:** `POST /api/v1/bookings/{confirmationNumber}/cancel`
**Actor:** Authenticated user (owner of the booking)
**Owned by:** booking-service

### Steps

```
1. LOAD ALL BOOKINGS (Postgres read, uses idx_bookings_confirmation)
   SELECT b.*, s.start_time, s.venue_id FROM bookings b
   JOIN slots s ON b.slot_id = s.slot_id
   WHERE b.confirmation_number = :confirmationNumber
   (No status filter — load all regardless of current status)
   If none → 404 NOT_FOUND

2. AUTHORIZE
   Assert booking.user_id = JWT userId for every row → 403 FORBIDDEN if mismatch

3. CONVERGENCE CHECK (idempotency)
   Partition rows into CONFIRMED and CANCELLED sets
   If CONFIRMED set is empty → all already cancelled → return 200 with current data
   Proceed with CONFIRMED set only for steps 4–7

4. CHECK CANCELLATION WINDOW
   For each booking in CONFIRMED set: assert s.start_time > now() + interval '24 hours'
   If ANY is inside the window → 409 CANCELLATION_WINDOW_CLOSED
   (Collective check on CONFIRMED items only — already-CANCELLED items are not re-evaluated)

5. POSTGRES TRANSACTION
   BEGIN;
     UPDATE bookings
       SET status = 'CANCELLED', cancelled_at = now()
       WHERE confirmation_number = :confirmationNumber AND status = 'CONFIRMED';
     UPDATE holds SET status = 'RELEASED'
       WHERE session_id = :sessionId AND status = 'CONFIRMED';
     UPDATE slots SET status = 'AVAILABLE'
       WHERE slot_id IN (:confirmedSlotIds) AND status = 'BOOKED';
   COMMIT;
   If transaction fails → rollback → 500

6. INVALIDATE CACHE
   DEL slots:{venueId}:{date}
   (One DEL per date if slots span multiple calendar days)

7. RETURN 200
   { confirmationNumber, cancelledBookings: [{ bookingId, slotId, startTime }, …] }
   (Includes all bookings now in CANCELLED state — both newly and previously cancelled)
```

### Failure Paths

| Failure | Behaviour |
|---------|-----------|
| confirmationNumber not found | 404 |
| booking.user_id ≠ JWT userId | 403 `FORBIDDEN` |
| All bookings already `CANCELLED` | 200 with existing data (idempotent replay) |
| Any CONFIRMED slot inside 24h window | 409 `CANCELLATION_WINDOW_CLOSED` |
| Postgres transaction fails | rollback → 500 |
| Cache DEL fails | stale cache expires within 5s; no correctness impact |

### Decisions

- **Cancellation is idempotent and convergent** — the endpoint loads all
  bookings for the `confirmationNumber` without a status filter. It cancels
  only the remaining CONFIRMED items; returns 200 if all are already CANCELLED.
  Mixed-status sessions (possible via admin action or incident recovery) are
  handled gracefully.
- **24h window check applies to CONFIRMED items only** — already-CANCELLED
  bookings are not re-evaluated.
- **Hold records set to `RELEASED` on cancellation** — reflects the full
  lifecycle in the audit trail (ACTIVE → CONFIRMED → RELEASED).
- **Slot UPDATE guarded with `AND status = 'BOOKED'`** — belt-and-suspenders
  against double-releasing a slot.
- **Slot released immediately** (`status = 'AVAILABLE'`) — no grace period; the
  slot is re-bookable by anyone the moment cancellation commits.
- **No Redis `hold:{slotId}` cleanup needed** — by cancellation time the
  booking is already CONFIRMED and the hold Redis keys were deleted at confirm time.

---

## 7. Flow 5 — Availability Query

**Endpoint:** `GET /api/v1/venues/{venueId}/slots?date={date}&status={status}`
**Actor:** Authenticated user
**Owned by:** booking-service (or a merged venue/availability-service — TBD Section 5)

### Steps

```
1. CHECK REDIS CACHE
   GET slots:{venueId}:{date}
   Cache hit → deserialize JSON array → go to step 5

2. CACHE MISS — QUERY POSTGRES (uses idx_slots_venue_start)
   SELECT slot_id, start_time, status FROM slots
   WHERE venue_id = :venueId
     AND DATE(start_time AT TIME ZONE 'UTC') = :date  -- :date is ISO 8601 (YYYY-MM-DD)
   ORDER BY start_time ASC
   (UTC partitioning is acceptable for Phase 0 — see Decisions)

3. APPLY STATUS FILTER (in application layer)
   If ?status= provided, filter the result list in memory
   (Not pushed to SQL to keep the cached payload complete and filter-agnostic)

4. WRITE TO CACHE
   SET slots:{venueId}:{date} {jsonArray} EX 5

5. DERIVE END TIME
   For each slot in response: endTime = startTime + 60 minutes
   (Derived in application code; not stored in Postgres)

6. RETURN 200
   { slots: [{ slotId, startTime, endTime, status }, …] }
```

### Staleness & Cache Invalidation

| Scenario | Behaviour |
|----------|-----------|
| Cache returns stale AVAILABLE slot | User attempts hold → SETNX fails → 409; no double-booking |
| Cache returns stale HELD/BOOKED slot | User sees slot as unavailable; up to 5s delay before it reappears |
| Hold created | Explicit `DEL slots:{venueId}:{date}` + 5s TTL as fallback |
| Booking confirmed | Explicit `DEL slots:{venueId}:{date}` + 5s TTL as fallback |
| Booking cancelled | Explicit `DEL slots:{venueId}:{date}` + 5s TTL as fallback |
| Hold expired (polling job) | No cache DEL — stale HELD entry expires within 5s |

### Decisions

- **OQ-10 RESOLVED — polling (frontend, every 5s)** — the 5s cache TTL and
  frontend poll interval are intentionally aligned. SSE and WebSockets are
  overkill for a read-only status display; polling is simpler to implement,
  test, and operate at 10,000 DAU.
- **Status filter applied in application layer, not SQL** — the cache stores the
  complete slot list for a venue+date. Filtering in SQL would require caching
  one entry per status value or bypassing the cache for filtered requests.
- **`endTime` is always derived** — never stored in Postgres, always computed
  from `startTime + seatlock.slot.duration-minutes`.
- **Date format is ISO 8601 (`YYYY-MM-DD`)** — used in both the `?date=` query
  parameter and the `slots:{venueId}:{date}` cache key.
- **UTC date partitioning (Phase 0)** — `DATE(start_time AT TIME ZONE 'UTC') = :date`
  is acceptable for Phase 0. Venue-timezone-aware date filtering (where a slot
  at 01:00 UTC could fall on the prior calendar day in a local timezone) is
  deferred to a future phase.

---

## 8. Cross-Cutting Decisions

| # | Decision | Rationale |
|---|----------|-----------|
| D-4.1 | Redis SETNX is the primary double-booking prevention gate | Sub-millisecond, atomic, gives per-slot TTL natively. No Postgres row lock held during Redis call. |
| D-4.2 | Postgres `slot.status` updated at hold time (not only at booking time) | Ensures availability queries that miss the Redis cache reflect the correct state. |
| D-4.3 | Hold expiry via `@Scheduled` polling every 60s (not Redis keyspace notifications) | Simpler to test and operate; keyspace notifications require non-default ElastiCache config; 60s lag is acceptable given booking path independently validates Redis keys. |
| D-4.9 | Expiry job uses `SELECT ... FOR UPDATE SKIP LOCKED` with batch size ≤500 | Prevents multiple booking-service instances from double-expiring the same hold. `AND status = 'ACTIVE'` on the UPDATE is belt-and-suspenders. Batch transactions over per-row transactions reduce round-trips; SKIP LOCKED partitions work across instances automatically. |
| D-4.4 | Cache TTL = 5s; frontend polls every 5s | Aligned intentionally so clients see at most one stale render before refresh. |
| D-4.5 | Explicit cache DEL on all writes (hold/confirm/cancel) in addition to TTL | Minimises the window in which a stale cache entry can mislead users; TTL is the safety net, DEL is the fast path. |
| D-4.6 | OQ-10 RESOLVED — polling (frontend, 5s) | SSE/WS overkill for read-only status; polling matches cache TTL. |
| D-4.7 | OQ-12 RESOLVED — URL path prefix `/api/v1/` | Simple, already adopted in all shapes; header versioning deferred to a future version. |
| D-4.8 | OQ-11 DEFERRED — idempotency formally out of scope for Phase 0 | UNIQUE constraints on `holds (session_id, slot_id)` and `bookings (session_id, slot_id)` are a partial guard against retry storms. Full idempotency-key support (Redis-backed, with TTL) is a Phase 1 concern. |
