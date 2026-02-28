# Milestone 0 — Phase 0 Complete: System Design Sign-Off

## Date
2026-02-27

## Status
**COMPLETE** — All Phase 0 deliverables produced. No implementation code written. Ready to begin Phase 1 — Foundation.

---

## What Phase 0 Produced

### Design Interview (6 Sections)

| Section | Document | Status |
|---------|----------|--------|
| 1 — Requirements | `docs/system-design/01-requirements.md` | ✅ |
| 2 — Core Entities | `docs/system-design/02-core-entities.md` | ✅ |
| 3 — API / Interface | `docs/system-design/03-api-interface.md` | ✅ |
| 4 — Data Flow | `docs/system-design/04-data-flow.md` | ✅ |
| 5 — High-Level Design | `docs/system-design/05-high-level-design.md` | ✅ |
| 6 — Deep Dives | `docs/system-design/06-deep-dives.md` | ✅ |

### Diagrams (5 Diagrams)

| Diagram | Document | Status |
|---------|----------|--------|
| 1 — System Context | `docs/diagrams/01-system-context.md` | ✅ |
| 2 — Service Architecture | `docs/diagrams/02-service-architecture.md` | ✅ |
| 3 — Booking Flow Sequence | `docs/diagrams/03-booking-flow-sequence.md` | ✅ |
| 4 — Data Model ERD | `docs/diagrams/04-data-model-erd.md` | ✅ |
| 5 — Infrastructure | `docs/diagrams/05-infrastructure.md` | ✅ |

### Architecture Decision Records (8 ADRs)

| ADR | Title | Document |
|-----|-------|----------|
| ADR-001 | Microservices Architecture | `docs/decisions/ADR-001.md` |
| ADR-002 | Redis SETNX as Primary Concurrency Gate | `docs/decisions/ADR-002.md` |
| ADR-003 | booking-service Owns Hold Lifecycle | `docs/decisions/ADR-003.md` |
| ADR-004 | availability-service Merged into venue-service | `docs/decisions/ADR-004.md` |
| ADR-005 | Async SQS for Notifications | `docs/decisions/ADR-005.md` |
| ADR-006 | Shared Postgres Cluster (Phase 0 Compromise) | `docs/decisions/ADR-006.md` |
| ADR-007 | Service JWT for Inter-Service Auth | `docs/decisions/ADR-007.md` |
| ADR-008 | Postgres-Before-Redis-DEL Operation Order | `docs/decisions/ADR-008.md` |

---

## Confirmed Services

| Service | Core Responsibilities |
|---------|-----------------------|
| **user-service** | Registration, login, JWT issuance, user profile |
| **venue-service** | Venue CRUD, slot CRUD + auto-generation, availability reads (Redis cache + Postgres fallback) |
| **booking-service** | Hold creation, confirmation, cancellation, booking history, hold expiry job, Redis cache invalidation, slot.status writes |
| **notification-service** | Email + SMS + in-app dispatch (stateless SQS consumer) |

---

## Key Numbers

| Metric | Value |
|--------|-------|
| Daily active users | 10,000 |
| Bookings per day | ~1,000 |
| Peak concurrent users | 200 |
| Peak hold creation rate | ~50 holds/min |
| Hold duration | 30 minutes |
| Cancellation window | Up to 24h before reservation |
| p99 browse latency target | < 200ms |
| p99 hold latency target | < 500ms |
| Uptime SLA | 99.9% |
| Browse staleness tolerance | Up to 5 seconds |

---

## All Open Questions Resolved

| # | Question | Resolution |
|---|----------|------------|
| OQ-01–09 | Core entity design | Resolved in Section 2 |
| OQ-10 | Real-time availability strategy | Frontend polls every 5s |
| OQ-11 | Idempotency strategy | Idempotency-Key on POST /holds; sessionId dedup on POST /bookings |
| OQ-12 | API versioning | URL path prefix `/api/v1/` |
| OQ-13 | Request/response shapes | Fully defined in 03-api-interface.md |
| OQ-14 | Rate limiting | POST /holds rate-limited (numeric limits Phase 1) |
| OQ-15 | availability-service boundary | Merged into venue-service |
| OQ-16 | Sync vs. async communication | booking→notification: async SQS; booking→venue: sync HTTP + shared Postgres; user→booking: no runtime call |
| OQ-17 | Service discovery | AWS Cloud Map |
| OQ-18 | Concurrency strategy | Redis SETNX primary; Postgres AND status guard secondary |
| OQ-19 | Crash recovery mid-confirmation | Reorder ops; add DEL to cancellation |
| OQ-20 | Cache invalidation strategy | DEL + 5s TTL sufficient |
| OQ-21 | Redis unavailable fallback | Read-only degraded mode; POST /holds → 503 |
| OQ-22 | Vault unreachable on startup | Fail fast on startup; hold in-memory at runtime |

---

## Known Phase 0 Compromises (Deferred to Phase 1+)

| Compromise | Target phase | Description |
|------------|-------------|-------------|
| Shared Postgres cluster | Phase 1 | booking-service writes slots.status directly into venue-service's schema. Extract slot_availability into booking-service's DB. |
| Full Redis-backed idempotency store | Phase 1 | POST /holds and POST /bookings use in-process dedup only; no Redis TTL-keyed response cache. |
| Vault HA | Phase 1+ | Vault runs as a single ECS task; no HA cluster. |
| Venue-timezone-aware date filtering | Phase 1+ | Date partitioning for slots uses UTC. |
| Rate limiting numeric limits | Phase 1 | POST /holds is identified as rate-limited; specific limits (req/user/min) not yet defined. |
| mTLS / asymmetric JWT for inter-service auth | Phase 2+ | Service JWT uses symmetric HMAC; RS256 asymmetric is the longer-term goal. |
| Account lifecycle events | Phase 1+ | User deletion/suspension async events from user-service to booking-service not implemented. |

---

## Starting State for Phase 1

Phase 1 goal: **User auth + venue catalog. No booking logic yet.**

Deliverables:
- `user-service` — registration, login, JWT issuance (Spring Security + JWT)
- `venue-service` — venue CRUD, slot CRUD, slot auto-generation
- Docker Compose local development setup
- Flyway migrations for both services
- Basic CI with GitHub Actions (build + test on PR)

The double-booking problem (booking-service, Redis holds, expiry job) is Phase 2.
