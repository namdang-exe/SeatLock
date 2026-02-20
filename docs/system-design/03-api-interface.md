# 03 — API Interface

## Context

This document defines the full HTTP API surface for SeatLock as agreed in the Section 3 design interview (User Feedback Round 1). It covers endpoint shapes, request/response bodies, error codes, and the rationale behind key HTTP decisions.

All endpoints are prefixed with `/api/v1/`. Authentication is via JWT Bearer token unless noted.

---

## Decision Log

| Decision | Resolution |
|----------|------------|
| HTTP status for `POST /bookings` | **201 Created** — correct per HTTP semantics; POST creates new Booking records on the server. 200 OK is reserved for operations that succeed without creating a resource. |
| `confirmationNumber` scope | **Per session**, not per Booking record. One confirmation number covers all slots booked in a single transaction. |
| Cancellation granularity | **Session-level** — `POST /bookings/{confirmationNumber}/cancel` cancels every Booking in the session atomically. |
| Multi-slot confirmation | **All-or-nothing** — if any hold in the session has expired, the entire confirmation is rejected with 409. No partial confirms. |
| Slot query filters | `GET /venues/{venueId}/slots` accepts `?date=` (strongly recommended) and `?status=` (optional). |
| Cancel endpoint path identifier | **`confirmationNumber`** (not `sessionId`) — it is the user-facing booking reference that exists at cancel time, consistent with `GET /bookings` grouping, and useful for customer support. `sessionId` is internal hold-phase plumbing that should not drive the URL contract. |

---

## Endpoint Reference

### Auth

#### POST /api/v1/auth/register

Create a new user account.

**Request:**
```json
{
  "email": "user@example.com",
  "password": "s3cur3p@ss",
  "phone": "+15551234567"
}
```

**Response 201:**
```json
{
  "userId": "uuid-user",
  "email": "user@example.com"
}
```

**Errors:**

| Code | Error | Condition |
|------|-------|-----------|
| 409 | `EMAIL_ALREADY_EXISTS` | Email is already registered |
| 400 | `VALIDATION_ERROR` | Missing or malformed fields |

---

#### POST /api/v1/auth/login

Authenticate and receive a JWT.

**Request:**
```json
{
  "email": "user@example.com",
  "password": "s3cur3p@ss"
}
```

**Response 200:**
```json
{
  "token": "eyJhbGciOiJIUzI1NiIs...",
  "expiresAt": "2024-02-18T23:59:59Z"
}
```

**Errors:**

| Code | Error | Condition |
|------|-------|-----------|
| 401 | `INVALID_CREDENTIALS` | Wrong email or password |

---

### Venues & Slots

#### GET /api/v1/venues

Browse active venues.

**Response 200:**
```json
{
  "venues": [
    {
      "venueId": "uuid-v1",
      "name": "Conference Room A",
      "address": "123 Main St",
      "city": "Springfield",
      "state": "IL",
      "status": "ACTIVE"
    }
  ]
}
```

---

#### GET /api/v1/venues/{venueId}/slots

Browse slots for a venue. `date` is strongly recommended to scope results to a single calendar day. Without it, results may be large and unbounded.

**Query parameters:**

| Parameter | Required | Description |
|-----------|----------|-------------|
| `date` | Strongly recommended | ISO 8601 date — `2024-02-18`. Scopes results to that calendar day. |
| `status` | Optional | One of `AVAILABLE`, `HELD`, `BOOKED`. Omit to return all statuses. |

**Example:** `GET /api/v1/venues/uuid-v1/slots?date=2024-02-18&status=AVAILABLE`

**Response 200:**
```json
{
  "venueId": "uuid-v1",
  "date": "2024-02-18",
  "slots": [
    {
      "slotId": "uuid-s1",
      "startTime": "2024-02-18T09:00:00Z",
      "endTime": "2024-02-18T10:00:00Z",
      "status": "AVAILABLE"
    },
    {
      "slotId": "uuid-s2",
      "startTime": "2024-02-18T10:00:00Z",
      "endTime": "2024-02-18T11:00:00Z",
      "status": "AVAILABLE"
    }
  ]
}
```

**Errors:**

| Code | Error | Condition |
|------|-------|-----------|
| 404 | `VENUE_NOT_FOUND` | No active venue with that ID |
| 400 | `INVALID_DATE` | `date` param is not a valid ISO 8601 date |

---

### Holds

#### POST /api/v1/holds

Place a hold on one or more slots. All holds in the request share the same `sessionId` and the same `expiresAt` (they are created simultaneously, so they expire simultaneously).

**Required header:** `Idempotency-Key: <uuid>` — the server uses this value as the `sessionId`. On a duplicate request with the same key, the existing ACTIVE holds are returned directly without re-executing hold logic.

**Request:**
```json
{
  "slotIds": ["uuid-s1", "uuid-s2"]
}
```

**Response 200:**
```json
{
  "sessionId": "uuid-session",
  "expiresAt": "2024-02-18T15:30:00Z",
  "holds": [
    { "holdId": "uuid-h1", "slotId": "uuid-s1" },
    { "holdId": "uuid-h2", "slotId": "uuid-s2" }
  ]
}
```

**Errors:**

| Code | Error | Condition |
|------|-------|-----------|
| 409 | `SLOT_NOT_AVAILABLE` | One or more slots are already HELD or BOOKED. The response body identifies which slot(s). |
| 400 | `VALIDATION_ERROR` | `slotIds` is empty or contains invalid UUIDs |
| 400 | `MISSING_IDEMPOTENCY_KEY` | `Idempotency-Key` header is absent |
| 404 | `SLOT_NOT_FOUND` | One or more slot IDs do not exist |

**409 body (slot conflict):**
```json
{
  "error": "SLOT_NOT_AVAILABLE",
  "message": "One or more requested slots are not available.",
  "unavailableSlotIds": ["uuid-s2"]
}
```

**Behaviour notes:**
- If any slot in the request cannot be held, **no holds are created** (all-or-nothing).
- The `expiresAt` for all holds is `now + 30 minutes`.
- The Redis key `hold:{slotId}` is set via SETNX with a 30-minute TTL for each slot.

---

### Bookings

#### POST /api/v1/bookings

Confirm all holds in a session. This is an **all-or-nothing** operation: if any hold in the session has expired, no bookings are created.

**Optional header:** `Idempotency-Key: <uuid>` — if CONFIRMED bookings already exist for the given `sessionId`, the existing booking data is returned as 201 without re-executing confirmation logic.

**Processing steps:**
1. Idempotency check: if CONFIRMED bookings already exist for `sessionId`, return 201 with existing data.
2. Load all ACTIVE Hold records for the `sessionId` from Postgres (404 if none).
3. Verify every Redis key `hold:{slotId}` still exists and the stored holdId matches the Postgres record.
   - Key missing → 409 `HOLD_EXPIRED`
   - Key present but holdId mismatch → 409 `HOLD_MISMATCH`
4. If all holds are valid → atomically:
   - Create all Booking records with the same `confirmationNumber` and `sessionId`.
   - Set all Slot statuses → `BOOKED`.
   - Delete all Redis hold keys.
   - Update all Postgres Hold statuses → `CONFIRMED`.
5. Return 201 with the single `confirmationNumber` and the list of booked slots.

**Request:**
```json
{
  "sessionId": "uuid-session"
}
```

**Response 201:**
```json
{
  "confirmationNumber": "SL-20240218-4821",
  "sessionId": "uuid-session",
  "bookings": [
    { "bookingId": "uuid-b1", "slotId": "uuid-s1", "status": "CONFIRMED" },
    { "bookingId": "uuid-b2", "slotId": "uuid-s2", "status": "CONFIRMED" }
  ]
}
```

**Errors:**

| Code | Error | Condition |
|------|-------|-----------|
| 409 | `HOLD_EXPIRED` | One or more Redis hold keys are missing — the hold has expired. User must restart from `POST /holds`. |
| 409 | `HOLD_MISMATCH` | Redis hold key exists but holdId does not match the Postgres record (stale state). Refresh availability and create a new hold. |
| 404 | `SESSION_NOT_FOUND` | No active holds exist for the given `sessionId` |
| 400 | `VALIDATION_ERROR` | `sessionId` is missing or not a valid UUID |

**409 body (hold expired):**
```json
{
  "error": "HOLD_EXPIRED",
  "message": "One or more holds in this session have expired. Please start a new hold."
}
```

**409 body (hold mismatch):**
```json
{
  "error": "HOLD_MISMATCH",
  "message": "Hold state mismatch. Refresh availability and create a new hold."
}
```

---

#### POST /api/v1/bookings/{confirmationNumber}/cancel

Cancel all bookings in a session. The `confirmationNumber` identifies the session. `userId` is taken from the JWT — no request body.

**Why `confirmationNumber` and not `sessionId` in the URL?**
Both identifiers are available at cancel time — cancel is a post-confirmation-only action, so by definition both exist. `confirmationNumber` is chosen because:
- It is the resource identifier the user actually experiences ("my booking SL-20240218-4821"), matching the `GET /bookings` grouping key and what support staff look up.
- `sessionId` is internal transaction infrastructure that bridges the hold phase to the confirmation phase; it should not leak into the user-facing URL contract.
- The JWT `userId` check (403 `FORBIDDEN` if `userId` does not match the booking's owner) is the real authorization gate — path-segment obscurity is not relied upon for security.

This endpoint is **idempotent and convergent**: it cancels any remaining `CONFIRMED` bookings under the `confirmationNumber`; if all are already `CANCELLED` it returns 200 with the current data. The 24h rule is evaluated against **CONFIRMED items only** — already-cancelled bookings are not re-evaluated.

**Request body:** none

**Response 200:**
```json
{
  "confirmationNumber": "SL-20240218-4821",
  "cancelledAt": "2024-02-18T12:00:00Z",
  "bookings": [
    { "bookingId": "uuid-b1", "slotId": "uuid-s1", "status": "CANCELLED" },
    { "bookingId": "uuid-b2", "slotId": "uuid-s2", "status": "CANCELLED" }
  ]
}
```

**Errors:**

| Code | Error | Condition |
|------|-------|-----------|
| 409 | `CANCELLATION_WINDOW_CLOSED` | At least one slot in the session starts within 24 hours |
| 404 | `BOOKING_NOT_FOUND` | No bookings exist for this confirmation number |
| 403 | `FORBIDDEN` | JWT userId does not match the booking's userId |

**409 body (window closed):**
```json
{
  "error": "CANCELLATION_WINDOW_CLOSED",
  "message": "Cancellation is not allowed within 24 hours of the reservation."
}
```

---

#### GET /api/v1/bookings

Retrieve all bookings for the authenticated user, grouped by confirmation number (i.e., by session).

**Response 200:**
```json
{
  "bookings": [
    {
      "confirmationNumber": "SL-20240218-4821",
      "sessionId": "uuid-session",
      "status": "CONFIRMED",
      "slots": [
        { "bookingId": "uuid-b1", "slotId": "uuid-s1" },
        { "bookingId": "uuid-b2", "slotId": "uuid-s2" }
      ]
    }
  ]
}
```

**Notes:**
- `status` at the session level is `CONFIRMED` if all bookings in the group are `CONFIRMED`, or `CANCELLED` if all are `CANCELLED`.
- Results are ordered by `createdAt` descending (most recent first).
- `userId` is derived from the JWT — no query parameter needed.

---

### Admin Endpoints

#### GET /api/v1/admin/venues/{venueId}/bookings

List all confirmed bookings for a venue. Requires an admin JWT role claim.

**Query parameters:**

| Parameter | Required | Description |
|-----------|----------|-------------|
| `date` | Optional | Scope to a single calendar day |

**Response 200:**
```json
{
  "venueId": "uuid-v1",
  "bookings": [
    {
      "confirmationNumber": "SL-20240218-4821",
      "userId": "uuid-user",
      "slots": [
        { "bookingId": "uuid-b1", "slotId": "uuid-s1", "startTime": "2024-02-18T09:00:00Z" }
      ]
    }
  ]
}
```

---

## HTTP Status Code Summary

| Code | Meaning in SeatLock |
|------|---------------------|
| 200 | Success — operation completed, no new resource created (GET, cancel) |
| 201 | Created — new Booking or User resource created (POST /bookings, POST /auth/register) |
| 400 | Bad request — client-side validation failure (missing field, invalid UUID/date) |
| 401 | Unauthenticated — missing or invalid JWT |
| 403 | Forbidden — authenticated but not authorised for the resource |
| 404 | Not found — resource does not exist or does not belong to this user |
| 409 | Conflict — business rule violation (slot unavailable, hold expired, cancellation window closed) |

---

## Open Questions Resolved in This Section

| # | Resolution |
|---|------------|
| OQ-13 | Request/response shapes are fully defined above for all critical paths. |
| OQ-14 | Rate limiting scope: at minimum the `POST /holds` endpoint (prevents slot squatting). Specific limits (e.g., 10 hold requests/user/minute) to be confirmed — not blocking for Phase 0 design. |
