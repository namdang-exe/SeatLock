# 06 — Deep Dives

## Context

This document captures the four deep-dive decisions from the Section 6 design interview. Each deep dive addresses a failure scenario or architectural edge case that the high-level design (Section 5) deliberately deferred.

Earlier sections define requirements (01), entities (02), API shapes (03), data flows (04), and service topology (05). This document defines how the system behaves when things go wrong.

---

## Deep Dive A — OQ-19: Crash Recovery Mid-Confirmation

### The Scenario

A user submits `POST /bookings`. booking-service starts the confirmation sequence using the **original** operation order:

```
1. Validate all Redis hold keys exist
2. DEL hold:{slotId} for each slot     ← Redis keys gone
3. Postgres transaction:
     INSERT INTO bookings
     UPDATE slots SET status = 'BOOKED'
     UPDATE holds SET status = 'CONFIRMED'
                                        ← service crashes here
```

**Resulting state:**
- Redis keys deleted — holds look expired to any observer
- Postgres holds still `ACTIVE`; slots still `HELD`; no booking record exists
- User receives no response (connection dropped or 500)

### Root Cause

The failure window exists because Redis cleanup precedes the Postgres commit. If the service crashes in that window, there is no source-of-truth record of the intended booking, and Redis state is already destroyed.

### Fix 1 — Reorder Operations: Postgres Commit Before Redis DEL

**Decision:** Swap steps 2 and 3. Postgres becomes the commit point. Redis DEL becomes best-effort cleanup.

```
NEW order:
1. Validate all Redis hold keys exist
2. Postgres transaction:
     INSERT INTO bookings
     UPDATE slots SET status = 'BOOKED'
     UPDATE holds SET status = 'CONFIRMED'  ← source of truth committed here
3. DEL hold:{slotId} for each slot          ← best-effort, crash here is safe
```

**Why this works:**

| Crash point | State | What retry sees |
|-------------|-------|-----------------|
| Between step 1 and step 2 (before Postgres commits) | Redis keys intact; Postgres unchanged | Step 0 idempotency check finds no bookings → step 1 sees Redis keys → Postgres transaction runs → succeeds |
| Between step 2 and step 3 (Postgres committed, DEL never ran) | Postgres has CONFIRMED bookings; Redis has stale hold keys | Step 0 idempotency check finds existing CONFIRMED bookings → returns 201 with existing data immediately |

In both cases the user's retry succeeds without any special recovery logic. The existing step 0 idempotency check (added in Section 4) handles the second case for free.

### The Stale Redis Key Problem

With the new order, a crash after step 2 (Postgres committed) leaves stale `hold:{slotId}` keys in Redis. These keys have the original 30-minute TTL still running down.

**Problem:** If the booking is later cancelled, the slot returns to `AVAILABLE` in Postgres. A new user attempts `POST /holds` on that slot. `SETNX hold:{slotId}` fails — the stale key is still in Redis. The user gets 409 `SLOT_NOT_AVAILABLE` even though the slot is genuinely free.

### Fix 2 — Add DEL hold:{slotId} to the Cancellation Flow

**Decision:** Include `DEL hold:{slotId}` for each slot in the cancellation transaction cleanup, alongside the existing `DEL slots:{venueId}:{date}` cache invalidation.

Updated cancellation flow:

```
1. Load all bookings by confirmationNumber (no status filter)
2. Validate 24h cancellation window (CONFIRMED items only)
3. Postgres transaction:
     UPDATE slots SET status = 'AVAILABLE'  WHERE status = 'BOOKED'
     UPDATE bookings SET status = 'CANCELLED'
     UPDATE holds SET status = 'RELEASED'
4. DEL hold:{slotId}           ← added: cleans up stale crash artifact (no-op if key absent)
5. DEL slots:{venueId}:{date}  ← existing: availability cache invalidation
6. Publish BookingCancelledEvent → SQS
```

`DEL` on a missing Redis key is a no-op — this call is always safe regardless of whether a stale key exists.

### Key Decisions

| ID | Decision |
|----|----------|
| D-6.A.1 | Reorder `POST /bookings`: Postgres transaction commits before Redis DEL |
| D-6.A.2 | Add `DEL hold:{slotId}` to cancellation flow to clear crash-induced stale hold keys |

### Alternatives Rejected

| Alternative | Reason rejected |
|-------------|----------------|
| Saga pattern (compensating transactions) | Overkill — reordering two steps achieves the same safety guarantee without distributed coordination |
| Outbox pattern | Adds an outbox table and a background relay process; unnecessary when the simpler reorder eliminates the failure window |
| Accept and let TTL clean up | Stale keys block new holds after cancellation for up to 30 minutes — unacceptable UX |

---

## Deep Dive B — OQ-20: Cache Invalidation Strategy

### The Setup

venue-service maintains an availability cache:

```
key:   slots:{venueId}:{date}
value: full list of slots with current statuses
TTL:   5 seconds
```

booking-service issues `DEL slots:{venueId}:{date}` on every write that changes slot status:
- Hold creation (`AVAILABLE → HELD`)
- Booking confirmation (`HELD → BOOKED`)
- Cancellation (`BOOKED → AVAILABLE`)

venue-service repopulates the cache on cache miss by querying Postgres.

### The Race Condition

The one edge case DEL + TTL must handle:

```
T=0   booking-service DELs cache (hold creation, slot → HELD in Postgres)
T=1   venue-service reads Postgres (slot = HELD), about to SET cache
T=2   booking-service confirms booking:
        Postgres commits → slot = BOOKED
        DEL cache → key does not exist yet, no-op
T=3   venue-service SETs cache with slot = HELD  ← stale
      Cache: HELD.  Postgres: BOOKED.
T=8   TTL expires → cache evicted → next read re-populates with BOOKED ✅
```

The cache is stale for up to 5 seconds. This is within the 5-second browse staleness tolerance decided in Section 1. A user who sees `HELD` when the slot is actually `BOOKED` sees it as unavailable either way — no incorrect action is possible from this stale read. The stale data does not create a double-booking path.

### Decision: DEL + 5s TTL Is Sufficient

The current strategy is correct and complete for Phase 0.

- Explicit `DEL` is the fast path — clears stale data immediately after every write
- 5s TTL is the safety net — bounds worst-case staleness regardless of race conditions
- Postgres is always the source of truth on cache miss — no stale data persists beyond TTL

### Alternatives Rejected

| Alternative | Reason rejected |
|-------------|----------------|
| Write-through (booking-service updates cache directly) | Requires booking-service to know venue-service's cache schema; tight coupling between services; a failed cache write after Postgres commit leaves a stale positive entry (worse than a missing entry) |
| Pub/sub invalidation (booking-service publishes, venue-service subscribes) | Adds a topic and async subscriber; invalidation delivery lag may exceed the 5s TTL anyway; overkill for 4 services at 10k DAU |

### Key Decision

| ID | Decision |
|----|----------|
| D-6.B.1 | DEL + 5s TTL is the cache invalidation strategy. Write-through and pub/sub are not needed at this scale and staleness tolerance. |

---

## Deep Dive C — OQ-21: Redis Unavailable Fallback

### The Scenario

Redis / ElastiCache becomes unreachable. booking-service cannot execute `SETNX hold:{slotId}` — the primary double-booking gate is gone.

### Decision: Read-Only Degraded Mode

**`POST /holds` returns 503 `SERVICE_UNAVAILABLE` when Redis is unreachable.**

```json
{
  "error": "SERVICE_UNAVAILABLE",
  "message": "Booking temporarily unavailable. Please try again shortly."
}
```

Availability reads continue to work: venue-service falls back to Postgres on cache miss. `GET /venues/{venueId}/slots` remains functional. Users can browse but cannot book.

This is consistent with the degraded mode already defined in Section 1: "read-only browse when booking writes fail."

### Why Not Fall Back to Postgres SELECT FOR UPDATE

The alternative — falling back to pessimistic row-level locking in Postgres — was considered and rejected:

- Adds a second hold creation code path that must be tested and maintained independently
- Postgres lock contention under Redis-down conditions (a degraded scenario) introduces new failure modes at a moment when the system is already compromised
- At 10k DAU and ~50 holds/min peak, a short Redis outage is survivable as a read-only window; it does not justify the complexity of a fallback write path
- A failed Redis is likely a transient infrastructure event; ECS health checks and ElastiCache Multi-AZ replication minimise outage duration

### Key Decision

| ID | Decision |
|----|----------|
| D-6.C.1 | Redis unavailable → `POST /holds` returns 503; system enters read-only degraded mode. No Postgres `SELECT FOR UPDATE` fallback. |

### Alternatives Rejected

| Alternative | Reason rejected |
|-------------|----------------|
| Fallback to Postgres SELECT FOR UPDATE | Two write code paths; Postgres lock contention under already-degraded conditions; adds complexity for a survivable short outage |
| Accept holds without double-booking protection | Violates the zero-double-booking-tolerance requirement established in Section 1 |

---

## Deep Dive D — OQ-22: Vault Unreachable on Startup

### The Scenario

A service starts up (new ECS task launched) and cannot reach HashiCorp Vault to retrieve its secrets: database credentials, JWT signing keys, Redis credentials, third-party API keys.

### What Vault Holds

| Secret | Used by |
|--------|---------|
| User JWT signing secret | user-service (sign), all services (verify) |
| Service JWT signing secret | booking-service (sign), venue-service (verify) |
| Postgres credentials (per service) | user-service, venue-service, booking-service |
| Redis connection credentials | venue-service, booking-service |
| AWS SQS credentials | booking-service (publish), notification-service (consume) |
| SMTP / SMS / push API keys | notification-service |

### Decision: Fail Fast on Startup

**A service that cannot retrieve secrets from Vault at startup refuses to start and exits.**

ECS Fargate detects the failed container and relaunches it with exponential backoff. The task continues retrying until Vault is reachable and secrets are successfully retrieved.

**Why cached secrets are not used:**
- Secret rotation exists to revoke compromised credentials. A service that ignores rotation and starts on a cached secret defeats that guarantee entirely.
- A service running with a rotated JWT signing key will issue tokens that other services reject. A service running with revoked database credentials will fail at query time. The runtime errors are cryptic and harder to diagnose than a clean startup failure.
- Security correctness outweighs availability on startup — a service must not start in an unknown or potentially compromised secret state.

### Startup vs. Runtime Behaviour

Fail fast applies to startup only. The behaviour differs if Vault becomes unreachable after a service is already running:

| Timing | Behaviour |
|--------|-----------|
| Vault unreachable at **startup** | Fail fast — container exits; ECS restarts with backoff |
| Vault unreachable at **runtime** | Hold in-memory secrets for a grace period; expose health degraded via `/actuator/health`; alert via metrics; do **not** crash |

Crashing a running service because Vault had a 30-second blip during a periodic secret refresh would cause unnecessary traffic disruption. In-memory secrets are seconds-to-minutes old at most — a known, bounded staleness that is acceptable during a transient Vault outage.

### Implementation Note

Spring Cloud Vault (or a Vault Agent sidecar) handles the fail-fast behaviour at the Spring Boot application context level. If secret retrieval fails during context initialisation, the application context fails to load and the JVM exits with a non-zero code, triggering ECS task restart.

### Key Decision

| ID | Decision |
|----|----------|
| D-6.D.1 | Vault unreachable at startup → fail fast; ECS restarts the task. |
| D-6.D.2 | Vault unreachable at runtime → hold in-memory secrets; degrade health endpoint; do not crash. |

### Alternatives Rejected

| Alternative | Reason rejected |
|-------------|----------------|
| Start with cached secrets on startup | Defeats secret rotation; service may run with revoked credentials; runtime failures are cryptic and hard to diagnose |
| Crash on runtime Vault unavailability | A brief Vault blip (network hiccup, rolling restart) would kill running services mid-traffic; in-memory secrets are bounded-stale and safe for a short window |

---

## Open Questions Resolved in This Section

| # | Resolution |
|---|------------|
| OQ-19 | Reorder `POST /bookings`: Postgres commit before Redis DEL. Add `DEL hold:{slotId}` to cancellation flow to clear stale crash artifacts. |
| OQ-20 | DEL + 5s TTL is sufficient. Write-through and pub/sub rejected. |
| OQ-21 | Read-only degraded mode when Redis is unavailable. `POST /holds` returns 503. No Postgres SELECT FOR UPDATE fallback. |
| OQ-22 | Fail fast on startup. In-memory secret hold at runtime with health degradation. |

---

## All Section 6 Key Decisions

| ID | Decision |
|----|----------|
| D-6.A.1 | Reorder `POST /bookings`: Postgres commits before Redis DEL |
| D-6.A.2 | Add `DEL hold:{slotId}` to cancellation flow |
| D-6.B.1 | DEL + 5s TTL is the cache invalidation strategy |
| D-6.C.1 | Redis unavailable → 503 on `POST /holds`; read-only degraded mode |
| D-6.D.1 | Vault unreachable at startup → fail fast; ECS restarts |
| D-6.D.2 | Vault unreachable at runtime → in-memory hold; health degraded; no crash |
