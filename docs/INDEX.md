# SeatLock — Project Index

> Use this file to find anything in the project within 10 seconds.
> Updated when new files are added.

---

## Project Status

| Item | Status |
|------|--------|
| Phase 0 — System Design | ✅ COMPLETE |
| Phase 1+ — Implementation | NOT STARTED — see CODING_PLAN.md Stage 1 |
| Current implementation stage | None — ready to begin Stage 1 |

---

## Quick Navigation

| I want to... | Go to |
|-------------|-------|
| Find the current implementation stage | `docs/CODING_PLAN.md` → Stage Overview table |
| Understand the overall project plan | `docs/PROJECT_PLAN.md` |
| Look up an API endpoint shape | `docs/system-design/03-api-interface.md` |
| Look up a Postgres table schema / DDL | `docs/system-design/04-data-flow.md` → Section 1 |
| Look up the Redis key schema | `docs/system-design/04-data-flow.md` → Section 2 |
| Find the exact hold creation operation sequence | `docs/CODING_PLAN.md` → Stage 7 |
| Find the exact booking confirmation operation sequence | `docs/CODING_PLAN.md` → Stage 8 |
| Find the exact cancellation operation sequence | `docs/CODING_PLAN.md` → Stage 10 |
| Find the exact hold expiry job sequence | `docs/CODING_PLAN.md` → Stage 9 |
| Understand why a decision was made | `docs/decisions/ADR-00N.md` (see ADR index below) |
| See all architecture decisions summarised | `docs/CONTEXT.md` → Every Decision Made |
| See what's unresolved / deferred | `docs/CONTEXT.md` → Phase 0 Compromises |
| See the service architecture diagram | `docs/diagrams/02-service-architecture.md` |
| See the data model ERD | `docs/diagrams/04-data-model-erd.md` |
| See the AWS infrastructure layout | `docs/diagrams/05-infrastructure.md` |
| See the booking flow sequence diagrams | `docs/diagrams/03-booking-flow-sequence.md` |
| See the system context (who uses SeatLock) | `docs/diagrams/01-system-context.md` |
| Find what Vault stores | `docs/CONTEXT.md` → Vault Secret Inventory |
| Find the confirmed tech stack | `docs/CONTEXT.md` → Tech Stack |
| Find key numbers (DAU, latency targets, etc.) | `docs/CONTEXT.md` → Key Numbers |
| Find the Phase 0 sign-off | `docs/M0-phase0-complete.md` |
| See what happened in a past session | `docs/milestones/session-log.md` |

---

## Design Documents

### System Design (Phase 0 Interview)

| File | Contents |
|------|----------|
| `docs/system-design/01-requirements.md` | Functional requirements, non-functional targets, out-of-scope items |
| `docs/system-design/02-core-entities.md` | 5 entities: User, Venue, Slot, Hold, Booking — fields, state machines, ownership |
| `docs/system-design/03-api-interface.md` | All endpoints: request/response shapes, HTTP status codes, domain error codes |
| `docs/system-design/04-data-flow.md` | Postgres DDL (all 5 tables), Redis schema, 5 flow walkthroughs |
| `docs/system-design/05-high-level-design.md` | Service boundaries, communication patterns, service discovery, ALB routing, inter-service auth |
| `docs/system-design/06-deep-dives.md` | Crash recovery, cache invalidation, Redis fallback, Vault startup behaviour |

### Architecture Decision Records

| ADR | Decision |
|-----|---------|
| `docs/decisions/ADR-001.md` | Microservices architecture (vs monolith) |
| `docs/decisions/ADR-002.md` | Redis SETNX as primary concurrency gate (vs SELECT FOR UPDATE) |
| `docs/decisions/ADR-003.md` | booking-service owns Hold lifecycle (hold-service merged in) |
| `docs/decisions/ADR-004.md` | availability-service merged into venue-service |
| `docs/decisions/ADR-005.md` | Async SQS for booking → notification (vs sync HTTP) |
| `docs/decisions/ADR-006.md` | Shared Postgres cluster in Phase 0 (booking-service writes slots.status) |
| `docs/decisions/ADR-007.md` | Service JWT for inter-service auth (booking-service → venue-service) |
| `docs/decisions/ADR-008.md` | Postgres-before-Redis-DEL operation order in POST /bookings (crash safety) |

### Diagrams

| File | Type | Contents |
|------|------|----------|
| `docs/diagrams/01-system-context.md` | Context | External actors, SeatLock as black box, external systems |
| `docs/diagrams/02-service-architecture.md` | Architecture | All 4 services, ALB, Redis, SQS, Postgres, Vault, Cloud Map |
| `docs/diagrams/03-booking-flow-sequence.md` | Sequence | Hold creation, booking confirmation, hold expiry — all three flows |
| `docs/diagrams/04-data-model-erd.md` | ERD | All 5 tables, field types, PKs, FKs, indexes, service ownership |
| `docs/diagrams/05-infrastructure.md` | Infrastructure | AWS VPC, ECS, RDS, ElastiCache, ALB, SQS, Vault, CI/CD |

---

## Session Log

| File | Contents |
|------|----------|
| `docs/milestones/session-log.md` | Session-by-session log — what was done, decisions made, where to continue next |

---

## Implementation Plan

Full detail in `docs/CODING_PLAN.md`. Summary below.

| Stage | Name | Key output | Status |
|-------|------|-----------|--------|
| 1 | Project Scaffolding | Multi-module Gradle, Docker Compose, GitHub Actions CI | NOT STARTED |
| 2 | Testing Infrastructure | JUnit 5, Testcontainers, integration test base classes | NOT STARTED |
| 3 | user-service: Auth | Register, login, JWT issuance, Spring Security filter | NOT STARTED |
| 4 | venue-service: Venue + Slot CRUD | Venue/slot CRUD, slot auto-generation, admin endpoints | NOT STARTED |
| 5 | venue-service: Availability Cache | Redis cache for slots, cache miss → Postgres fallback | NOT STARTED |
| 6 | booking-service: Foundation + Service JWT | booking-service setup, Flyway migrations, Service JWT handshake | NOT STARTED |
| 7 | booking-service: Hold Creation | POST /holds — SETNX gate, all-or-nothing, Postgres writes | NOT STARTED |
| 8 | booking-service: Booking Confirmation | POST /bookings — crash-safe order, idempotency, confirmationNumber | NOT STARTED |
| 9 | booking-service: Hold Expiry Job | @Scheduled 60s, SKIP LOCKED, retry + batch halving | NOT STARTED |
| 10 | booking-service: Cancellation + History | POST cancel, GET history, admin view, stale key cleanup | NOT STARTED |
| 11 | notification-service | SQS consumer, email/SMS dispatch, ElasticMQ in Docker Compose | NOT STARTED |
| 12 | Resilience | Redis 503 degraded mode, Vault fail-fast, circuit breaker, retries | NOT STARTED |
| 13 | Observability | Actuator health, Prometheus metrics, Grafana dashboard | NOT STARTED |
| 14 | Frontend: Auth + Browse | React, login/register, venue browse, slot polling (5s) | NOT STARTED |
| 15 | Frontend: Booking Flows | Hold confirm, cancel, history, domain error messages | NOT STARTED |
| 16 | Infrastructure (AWS) | Terraform ECS+RDS+Redis+ALB, GitHub Actions deploy pipeline | NOT STARTED |

---

## Key Reference

### Confirmed Services

| Service | Port | DB | Key responsibilities |
|---------|------|----|---------------------|
| user-service | 8081 | user_db | Register, login, JWT issuance |
| venue-service | 8082 | venue_db (shared cluster) | Venue/slot CRUD, availability cache (Redis) |
| booking-service | 8083 | booking_db (shared cluster) | Holds, bookings, expiry job, cache invalidation |
| notification-service | 8084 | none (stateless) | SQS consumer, email/SMS dispatch |

### Domain Error Codes

| Code | HTTP | Endpoint |
|------|------|---------|
| `EMAIL_ALREADY_EXISTS` | 409 | POST /auth/register |
| `INVALID_CREDENTIALS` | 401 | POST /auth/login |
| `VENUE_NOT_FOUND` | 404 | GET /venues/{id}/slots |
| `SLOT_NOT_FOUND` | 404 | POST /holds |
| `SLOT_NOT_AVAILABLE` | 409 | POST /holds |
| `MISSING_IDEMPOTENCY_KEY` | 400 | POST /holds |
| `SESSION_NOT_FOUND` | 404 | POST /bookings |
| `HOLD_EXPIRED` | 409 | POST /bookings |
| `HOLD_MISMATCH` | 409 | POST /bookings |
| `BOOKING_NOT_FOUND` | 404 | POST /bookings/{id}/cancel |
| `CANCELLATION_WINDOW_CLOSED` | 409 | POST /bookings/{id}/cancel |
| `FORBIDDEN` | 403 | POST /bookings, POST /cancel |
| `SERVICE_UNAVAILABLE` | 503 | POST /holds (Redis down) |
| `VALIDATION_ERROR` | 400 | All write endpoints |

### Redis Keys

| Key | Value | TTL | Owner |
|-----|-------|-----|-------|
| `hold:{slotId}` | `{holdId, userId, sessionId, expiresAt}` JSON | 1800s (30 min) | booking-service (SETNX) |
| `slots:{venueId}:{date}` | JSON array of all slots for venue+date | 5s | venue-service (SET); booking-service (DEL) |

### Key Numbers

| Metric | Value |
|--------|-------|
| Hold duration | 30 minutes |
| Cancellation window | > 24h before slot start |
| Slot duration | 60 minutes (config: `seatlock.slot.duration-minutes`) |
| Slot auto-generation schedule | Mon–Fri, 09:00–17:00 UTC |
| Availability cache TTL | 5 seconds |
| Browse staleness tolerance | 5 seconds |
| p99 browse latency target | < 200ms |
| p99 hold latency target | < 500ms |
| Hold expiry job interval | 60 seconds |
| Expiry job batch size | ≤ 500 |
| Confirmation number format | `SL-{YYYYMMDD}-{4-digit}` |
| Service JWT TTL | 5 minutes |

### Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend | Java 21, Spring Boot 3.x, Spring Security, Spring Data JPA, Spring Data Redis |
| Database | PostgreSQL (RDS) — source of truth |
| Cache / Holds | Redis (ElastiCache) |
| Migrations | Flyway |
| Messaging | AWS SQS (ElasticMQ locally) |
| Service discovery | AWS Cloud Map |
| Secrets | HashiCorp Vault (Spring Cloud Vault) |
| Build | Gradle (Kotlin DSL), multi-module |
| Testing | JUnit 5, Testcontainers, Mockito/MockK, MockMvc |
| Resilience | Resilience4j (circuit breaker + retry) |
| Observability | Spring Actuator, Micrometer, Prometheus, Grafana |
| Infrastructure | Terraform, AWS ECS Fargate, Docker |
| Frontend | React 18, TypeScript, Vite, TanStack Query v5, Axios, Tailwind CSS |
| CI/CD | GitHub Actions |

---

## Phase 0 Compromises (to fix in Phase 1+)

| Compromise | Target | Description |
|------------|--------|-------------|
| Shared Postgres cluster | Phase 1 | booking-service writes `slots.status` directly |
| Full Redis-backed idempotency | Phase 1 | Currently in-process dedup only |
| Rate limiting numeric limits | Phase 1 | `POST /holds` needs explicit req/user/min limit |
| Vault HA | Phase 1+ | Single ECS task; no HA cluster |
| Venue-timezone date filtering | Phase 1+ | UTC date partitioning only |
| Asymmetric JWT (RS256) inter-service auth | Phase 2+ | Currently symmetric HMAC shared secret |
| Account lifecycle events | Phase 1+ | User deletion/suspension not propagated |
