# 01 — Requirements

## Context

SeatLock is a distributed reservation platform that allows users to browse venues, hold time slots, and confirm bookings. This document captures the agreed requirements from the Phase 0 design interview. These are the requirements we chose and why — alternatives considered are noted where relevant.

---

## Functional Requirements

| ID | Priority | Requirement |
|----|----------|-------------|
| FR-1 | P0 | Users browse venues and their available time slots |
| FR-2 | P0 | Users hold a venue time slot for **30 minutes** |
| FR-3 | P0 | Users confirm a booking (one or multiple slots per transaction) |
| FR-4 | P0 | Users cancel a booking up to **24 hours before** the reservation start time |
| FR-5 | P0 | Held and booked slots are immediately unavailable to other users |
| FR-6 | P0 | Notify users via email + in-app + SMS on: hold expired, booking confirmed, booking canceled |
| FR-7 | P0 | JWT-based authentication — registered users only, no guest booking |
| FR-8 | P0 | Admin: create venues and time slots |
| FR-9 | P0 | Admin: view confirmed bookings and booked venues |

### Alternatives Considered

- **Guest booking (no account required):** Rejected. JWT auth was chosen from the start; tracking holds and notification delivery requires a persistent user identity.
- **Payment processing in SeatLock:** Rejected as explicitly out of scope. Booking confirmation is the terminal action. Payment can be integrated externally in a later phase.
- **Admin force-cancel (override 24h rule):** Rejected for Phase 0. The cancellation window is user-enforced only.
- **Admin view of active holds:** Rejected. Holds are transient (30-min TTL) and do not need admin visibility. Only confirmed bookings are exposed to admin.
- **Notifications on hold created:** Rejected. Users receive notifications only on: hold expired, booking confirmed, booking canceled. No notification on hold creation to avoid noise.

---

## Non-Functional Requirements

| Target | Value | Rationale |
|--------|-------|-----------|
| Daily active users | 10,000 | Design target; actual launch ~1,000 users |
| Bookings per day | ~1,000 | ~10% of DAU convert to a booking |
| Peak concurrent users | 200 | Spike scenario: popular venue releases slots |
| Peak hold creation rate | ~50 holds/min | Derived from concurrent user spike |
| p99 browse latency | < 200ms | Read-heavy path; served from cache |
| p99 hold latency | < 500ms | Write path; involves Redis + Postgres |
| Uptime SLA | 99.9% | ~8.7 hours downtime/year acceptable |
| Degraded mode | Read-only browsing acceptable during partial outage | Users can browse but not book if write path is degraded |
| Browse consistency | Eventual — up to **5 seconds** of staleness acceptable | Allows serving availability from Redis cache |
| Hold/book consistency | **Strong** — no double-booking tolerated | Requires atomic operations; eventual consistency explicitly rejected for this path |

### Consistency Tradeoff Decision

We chose **eventual consistency for reads, strong consistency for writes**. Specifically:

- A user browsing available slots may see a slot as available that was held up to 5 seconds ago. This is acceptable — they will receive a conflict error if they attempt to hold it.
- A user placing a hold will always get an authoritative answer. The hold creation path uses Redis atomic operations (SETNX) as the concurrency gate, backed by Postgres as the source of truth.
- This tradeoff reduces read latency (served from Redis/cache at < 200ms p99) while preserving booking integrity.

---

## Out of Scope

| Item | Reason |
|------|--------|
| Payment processing | External concern; not part of reservation lifecycle |
| Guest booking | Requires persistent identity for holds and notifications |
| Admin force-cancel | Not needed for Phase 0 |
| Admin hold visibility | Holds are transient; not worth exposing |
| Recurring slot generation | Admin creates slots individually for Phase 0 |
| Mobile app | Frontend is React 18 web only |
| Multi-tenancy / white-label | Single SeatLock instance |

---

## Open Questions from This Section

- None — all requirements confirmed and prioritized.
