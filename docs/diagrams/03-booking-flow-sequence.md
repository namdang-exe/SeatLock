# Diagram 03 — Booking Flow Sequence

## Overview

Three sequence diagrams covering the core booking lifecycle:
1. Hold Creation (happy path)
2. Booking Confirmation (happy path — crash-safe operation order)
3. Hold Expiry (background job)

All flows originate from booking-service. venue-service handles availability reads separately (not shown here — see data flow doc).

---

## Flow 1 — Hold Creation

```mermaid
sequenceDiagram
    actor User
    participant ALB
    participant booking as booking-service
    participant venue as venue-service
    participant Redis
    participant Postgres
    participant Cache as Redis Cache<br/>(slots:{venueId}:{date})

    User->>ALB: POST /api/v1/holds<br/>{slotIds: [...], Idempotency-Key: <uuid>}
    ALB->>booking: route to booking-service

    Note over booking: Idempotency-Key becomes sessionId.<br/>Check if sessionId already has ACTIVE holds.

    booking->>Postgres: SELECT * FROM holds WHERE session_id = ? AND status = 'ACTIVE'
    Postgres-->>booking: (empty — first attempt)

    booking->>venue: GET /api/v1/internal/slots?ids=s1,s2<br/>Authorization: Bearer <service-jwt>
    venue->>Postgres: SELECT * FROM slots WHERE id IN (s1, s2)
    Postgres-->>venue: [{slotId, venueId, startTime, status}, ...]
    venue-->>booking: 200 {slots}

    Note over booking: All slots verified to exist.<br/>Proceed to Redis SETNX gate.

    loop For each slotId
        booking->>Redis: SETNX hold:{slotId} {holdId} EX 1800
        Redis-->>booking: OK (won) or nil (lost)
    end

    Note over booking: All SETNX succeeded.<br/>Write to Postgres atomically.

    booking->>Postgres: BEGIN<br/>INSERT INTO holds (holdId, sessionId, userId, slotId, expiresAt, status='ACTIVE') × N<br/>UPDATE slots SET status='HELD' WHERE id IN (...) AND status='AVAILABLE'<br/>COMMIT

    Note over booking: Postgres committed.<br/>Invalidate availability cache.

    booking->>Cache: DEL slots:{venueId}:{date}

    booking-->>ALB: 201 {sessionId, holds: [{holdId, slotId, expiresAt}, ...]}
    ALB-->>User: 201
```

**Failure paths (not shown):**
- Any SETNX returns nil → release any Redis keys already won → return 409 `SLOT_NOT_AVAILABLE`
- slot UPDATE row count mismatch → return 409 `SLOT_NOT_AVAILABLE` (secondary Postgres guard)
- venue-service unreachable → return 503

---

## Flow 2 — Booking Confirmation

```mermaid
sequenceDiagram
    actor User
    participant ALB
    participant booking as booking-service
    participant Redis
    participant Postgres
    participant Cache as Redis Cache<br/>(slots:{venueId}:{date})
    participant SQS
    participant notify as notification-service
    participant Provider as Email/SMS Provider

    User->>ALB: POST /api/v1/bookings<br/>{sessionId: <uuid>}
    ALB->>booking: route to booking-service

    Note over booking: Step 0 — Idempotency check.

    booking->>Postgres: SELECT * FROM bookings WHERE session_id = ?
    Postgres-->>booking: (empty — first attempt)

    Note over booking: Step 1 — Validate Redis hold keys.

    loop For each slotId in session
        booking->>Redis: GET hold:{slotId}
        Redis-->>booking: {holdId} (present) or nil (expired)
    end

    Note over booking: All keys present and holdIds match Postgres.<br/>Step 2 — Commit to Postgres FIRST (crash-safe order).

    booking->>Postgres: BEGIN<br/>INSERT INTO bookings (bookingId, sessionId, confirmationNumber, userId, slotId, holdId, status='CONFIRMED') × N<br/>UPDATE slots SET status='BOOKED' WHERE id IN (...) AND status='HELD'<br/>UPDATE holds SET status='CONFIRMED' WHERE session_id = ?<br/>COMMIT

    Note over booking: Postgres is now source of truth.<br/>Step 3 — Best-effort Redis cleanup.

    loop For each slotId
        booking->>Redis: DEL hold:{slotId}
    end

    booking->>Cache: DEL slots:{venueId}:{date}

    booking->>SQS: publish BookingConfirmedEvent<br/>{confirmationNumber, userId, slots, ...}

    booking-->>ALB: 201 {confirmationNumber, bookings: [...]}
    ALB-->>User: 201

    Note over SQS,Provider: Async — independent of user response.

    SQS-->>notify: consume BookingConfirmedEvent
    notify->>Provider: send confirmation email + SMS
```

**Failure paths (not shown):**
- Any Redis key missing → return 409 `HOLD_EXPIRED`
- holdId in Redis doesn't match Postgres → return 409 `HOLD_MISMATCH`
- Crash after Postgres commits, before Redis DEL → retry hits step 0 idempotency check → returns 201 with existing data

---

## Flow 3 — Hold Expiry (Background Job)

```mermaid
sequenceDiagram
    participant job as @Scheduled Job<br/>(booking-service, every 60s)
    participant Postgres
    participant Cache as Redis Cache<br/>(slots:{venueId}:{date})
    participant SQS
    participant notify as notification-service
    participant Provider as Email/SMS Provider

    Note over job: Runs every 60 seconds on all booking-service instances.<br/>Uses SKIP LOCKED to prevent double-expiry.

    job->>Postgres: SELECT h.* FROM holds h<br/>WHERE status = 'ACTIVE'<br/>AND expires_at < NOW()<br/>LIMIT 500<br/>FOR UPDATE SKIP LOCKED

    Postgres-->>job: [{holdId, sessionId, userId, slotId, venueId}, ...]

    Note over job: Batch update — atomic, belt-and-suspenders status guard.

    job->>Postgres: BEGIN<br/>UPDATE holds SET status='EXPIRED'<br/>WHERE hold_id IN (...) AND status='ACTIVE'<br/>UPDATE slots SET status='AVAILABLE'<br/>WHERE slot_id IN (...) AND status='HELD'<br/>COMMIT

    loop For each unique {venueId, date} in batch
        job->>Cache: DEL slots:{venueId}:{date}
    end

    loop For each expired hold (grouped by sessionId)
        job->>SQS: publish HoldExpiredEvent<br/>{sessionId, userId, slots}
    end

    SQS-->>notify: consume HoldExpiredEvent
    notify->>Provider: send hold expired email + SMS
```

**Notes:**
- `SKIP LOCKED` prevents multiple booking-service instances from processing the same holds
- On deadlock or timeout: retry up to 3× with exponential backoff; halve batch size on repeated failure
- Redis hold keys (`hold:{slotId}`) expire naturally via TTL; the job does not DEL them — they are already expired
