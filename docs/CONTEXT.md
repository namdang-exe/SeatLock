# SeatLock — Session Context

> **This file is fed to every new session alongside INDEX.md, CODING_PLAN.md, and open-questions.md.**
> At session end: update the Current Phase & Status table and note any new implementation decisions made.
> For full phase/milestone tracking see `docs/PROJECT_PLAN.md`.

---

## Current Phase & Status

| Item | Status |
|------|--------|
| Phase | **Phase 1 — Foundation** (IN PROGRESS — Stages 1–3 complete, Stage 4 next) |
| Part 1 — Design Interview | ✅ COMPLETE (all 6 sections) |
| Section 1 — Requirements | ✅ COMPLETE → `docs/system-design/01-requirements.md` |
| Section 2 — Core Entities | ✅ COMPLETE → `docs/system-design/02-core-entities.md` |
| Section 3 — API / Interface | ✅ COMPLETE → `docs/system-design/03-api-interface.md` |
| Section 4 — Data Flow | ✅ COMPLETE → `docs/system-design/04-data-flow.md` |
| Section 5 — High-Level Design | ✅ COMPLETE → `docs/system-design/05-high-level-design.md` |
| Section 6 — Deep Dives | ✅ COMPLETE → `docs/system-design/06-deep-dives.md` |
| Part 2 — Diagrams | ✅ COMPLETE (all 5 done) → `docs/diagrams/` |
| Part 2 — Documentation + ADRs | ✅ COMPLETE → `docs/system-design/`, `docs/decisions/` |
| Phase 0 milestone | ✅ COMPLETE → `docs/M0-phase0-complete.md` |

---

## Every Decision Made

### Tech Stack (confirmed, non-negotiable — do not re-raise in any session)

- We decided on **Java 21 + Spring Boot 3.x** as the backend because it is the project's confirmed stack.
- We decided on **PostgreSQL/RDS** as the source of truth because it provides the ACID guarantees required for booking integrity.
- We decided on **Redis/ElastiCache** for holds and availability caching because it provides sub-millisecond atomic operations (SETNX) and native TTL expiry — critical for the 30-minute hold mechanism.
- We decided on **AWS ECS Fargate** because it eliminates EC2 management while keeping the system cloud-native and horizontally scalable.
- We decided on **HashiCorp Vault** for secrets because it is the confirmed stack and provides dynamic secret rotation.
- We decided on **React 18 + TypeScript** for the frontend because it is the confirmed stack.
- We decided on **GitHub Actions + Terraform** for CI/CD and infrastructure-as-code because they are the confirmed DevOps tools.

### Requirements Decisions (Section 1 — complete)

- We decided the **bookable unit is a generic venue time slot** (not a numbered seat) because SeatLock models resources with available windows, not fixed seating layouts.
- We decided on a **30-minute hold duration** because it gives users enough time to confirm without locking slots for long periods.
- We decided on **JWT authentication with registered users only** (no guest booking) because holds and notification delivery require a persistent user identity.
- We decided **payment is out of scope** because booking confirmation is the terminal action; payment is an external concern for a later phase.
- We decided **cancellation is allowed up to 24 hours before the reservation** to protect venue operators from last-minute no-shows while giving users flexibility.
- We decided to **notify on hold expired, booking confirmed, and booking canceled only** (not on hold created) because a hold-created notification adds noise without meaningful user value.
- We decided to support **multiple slots per booking transaction** because users may need to book a venue across multiple time windows in a single flow.
- We decided **admin can create venues and time slots and view confirmed bookings** but **cannot view active holds or force-cancel bookings** because holds are transient and force-cancel is not needed in Phase 0.
- We decided on **eventual consistency for browse reads (up to 5s staleness acceptable)** because serving availability from Redis cache achieves the < 200ms p99 latency target; a stale read results in a hold-time conflict error, not a double-booking.
- We decided on **strong consistency for hold and booking writes (zero double-booking tolerance)** because a double-booked slot is a data integrity failure that cannot be corrected after the fact.
- We decided the system should support a **read-only degraded mode** (browse but not book) during a partial outage because availability reads and booking writes can be separated at the service boundary.

### Core Entity Decisions (Section 2 — complete)

- We decided **Venue has address, city, and state fields** because venues are physical spaces users need to locate.
- We decided **slot capacity is always 1** (one booking per slot, entire venue) because it keeps the concurrency model simple; configurable capacity is deferred to a future phase.
- We decided on **soft-delete for Venue** (`ACTIVE`/`INACTIVE` status) because hard-deleting a venue would destroy historical booking records.
- We decided **slot duration is a configurable system constant** (`seatlock.slot.duration-minutes=60`) because it should be changeable via config without a code deploy; current value is **60 minutes**.
- We decided **slots are auto-generated by a recurring rule** (Mon–Fri, 9am–5pm, 1h blocks) because manual creation of hundreds of individual slots is impractical for venue operators.
- We decided the **Slot state machine is `AVAILABLE → HELD → BOOKED → AVAILABLE`** (via cancellation); there is no `CANCELLED` state on Slot because cancellation history belongs on the Booking record.
- We decided **cancellation requires > 24h before slot start**; the slot is immediately re-bookable by anyone after cancellation.
- We decided **one Hold record per slot** with a shared `sessionId` to group multi-slot transactions, because independent per-slot TTLs in Redis are cleaner than a single TTL for a group.
- We decided **Hold carries `(holdId, sessionId, userId, slotId, expiresAt, status)`**; sessionId groups all holds in one user transaction.
- We decided **booking-service owns both Hold and Booking** because they are the same domain (reservation lifecycle); a separate hold-service would require a synchronous cross-service call on the critical confirmation path.
- We decided **Redis key is deleted on confirmation, Postgres Hold status moves to `CONFIRMED`** so Redis stays lean while the full audit trail lives in Postgres.
- We decided **Booking carries a human-readable `confirmationNumber`** (format: `SL-{YYYYMMDD}-{4-digit-suffix}`) because users need to quote it to venue operators.
- We decided **Booking state machine is `CONFIRMED → CANCELLED` only** — no `PENDING` state — because payment is out of scope and confirmation is immediate.

### API Decisions (Section 3 — complete)

- We decided **HTTP 201 is correct for `POST /bookings`** because the endpoint creates new Booking records on the server; 200 OK is reserved for operations that succeed without creating a resource.
- We decided **one `confirmationNumber` per session** (not per Booking record) because users expect a single reference that covers all slots booked together; a per-record number would require users to juggle multiple codes for one transaction.
- We decided `sessionId` is stored on every Booking record (redundantly alongside `confirmationNumber`) so a query by `confirmationNumber` returns all sibling bookings without joining back through Hold.
- We decided **cancellation is idempotent and convergent**: loads all bookings for the `confirmationNumber` without a status filter; cancels only remaining CONFIRMED items; returns 200 if all are already CANCELLED. Mixed-status sessions (possible via admin/incident) are handled gracefully. Partial cancellation (choosing specific slots) is still not supported in Phase 0.
- We decided the **24h cancellation rule applies collectively to CONFIRMED items only**: if any remaining CONFIRMED slot is inside the window the entire cancellation is rejected; already-CANCELLED items are not re-evaluated.
- We decided **Hold records are set to `RELEASED` on cancellation** — completing the full lifecycle audit trail (ACTIVE → CONFIRMED → RELEASED).
- We decided **`POST /bookings` is all-or-nothing**: if any hold in the session has expired, no bookings are created and a 409 is returned; partial confirmation is not supported.
- We decided **`GET /venues/{venueId}/slots` accepts `?date=` (strongly recommended) and `?status=` (optional)** to scope slot queries to a calendar day and filter by availability.
- We decided **`POST /holds` is also all-or-nothing**: if any requested slot is unavailable, no holds are created for any slot in the request.
- We decided the **error vocabulary uses domain-specific error codes** (`SLOT_NOT_AVAILABLE`, `HOLD_EXPIRED`, `CANCELLATION_WINDOW_CLOSED`, etc.) in the response body alongside the HTTP status, so clients can react programmatically without parsing message strings.
- We decided **admin endpoints require a JWT role claim** (not a separate admin token) to keep auth simple in Phase 0.
- We decided to keep **`confirmationNumber` (not `sessionId`) as the path identifier for `POST /bookings/{confirmationNumber}/cancel`** because `confirmationNumber` is the user-facing booking reference that both the frontend and support staff use; `sessionId` is internal hold-phase infrastructure that should not drive the API contract.
- We decided the **`sessionId`/`confirmationNumber` dual-key pattern on Booking is intentional**: each key serves a different semantic role across the hold-to-confirm lifecycle. `sessionId` (UUID) is the internal transaction identity; `confirmationNumber` (human-readable) is the user-facing booking reference. Storing both as indexed columns on every Booking row is deliberate denormalization to avoid joins back through the Hold table.

### Data Flow Decisions (Section 4 — complete)

- We decided **Redis SETNX is the primary double-booking prevention gate** (not `SELECT FOR UPDATE`) because SETNX is sub-millisecond, atomic, and gives per-slot TTL natively without holding a Postgres row lock.
- We decided **`slot.status` is updated to `HELD` in Postgres at hold time** so availability queries that miss the Redis cache still reflect the correct state.
- We decided **hold expiry uses a `@Scheduled` polling job every 60s** (not Redis keyspace notifications) because keyspace notifications require non-default ElastiCache config, and 60s lag is acceptable since the booking confirmation path independently validates Redis keys.
- We decided the **expiry job uses `SELECT ... FOR UPDATE SKIP LOCKED` with batch size ≤500** to prevent multiple instances from double-expiring the same hold. `AND status = 'ACTIVE'` on the holds UPDATE is belt-and-suspenders. On deadlock/timeout: retry up to 3× with backoff, then halve batch size.
- We decided **frontend polls for availability every 5s**, matching the 5s Redis cache TTL, because SSE/WebSockets are overkill for read-only status updates at 10,000 DAU (OQ-10 RESOLVED).
- We decided **explicit `DEL` on all writes (hold/confirm/cancel)** in addition to TTL to minimise the stale-cache window; TTL is the safety net, DEL is the fast path.
- We decided **URL path prefix `/api/v1/`** — simple, already adopted, header versioning deferred (OQ-12 RESOLVED).
- We decided **`Idempotency-Key` header is required on `POST /holds`** (OQ-11 partially resolved for Phase 0); its value becomes the `sessionId`. On retry with the same key, existing ACTIVE holds are returned without re-executing hold logic.
- We decided **`POST /bookings` is idempotent by `sessionId`**: step 0 always checks for existing CONFIRMED bookings; if found, returns 201 with existing data (no re-execution). `Idempotency-Key` header support follows the same pattern as `POST /holds`. Full Redis-backed idempotency store remains Phase 1.
- We decided to **split the Redis verification error in booking confirmation into two codes**: `HOLD_EXPIRED` (key missing — hold genuinely expired) and `HOLD_MISMATCH` (key present but holdId doesn't match Postgres record — rare stale-DEL race). Both return HTTP 409; `HOLD_MISMATCH` carries the message "Hold state mismatch. Refresh availability and create a new hold."
- We decided **`AND status = 'AVAILABLE'` is added to the Postgres slot UPDATE in hold creation** as a secondary safety net for the ≤60s @Scheduled expiry lag window (Redis key gone but slot still HELD in Postgres). Row-count mismatch returns 409 SLOT_NOT_AVAILABLE, not 500.
- We decided **status filter for `GET /venues/{venueId}/slots` is applied in the application layer**, not SQL, so the cache stores the complete slot list and any filtered variant can be served without a separate cache entry.
- We decided **date format is ISO 8601 (`YYYY-MM-DD`)** for both the `?date=` query parameter and the `slots:{venueId}:{date}` cache key.
- We decided **UTC is the date partitioning timezone for Phase 0** — venue-timezone-aware date filtering is deferred.
- We decided **`AND status = 'BOOKED'` is added to the Postgres slot UPDATE in cancellation** as a belt-and-suspenders guard against double-releasing a slot.

### High-Level Design Decisions (Section 5 — complete)

- We decided **`slot.status` stays on the Slot entity (Model A — denormalized status)** because availability is on the hot read path (every 5s poll) and we own all three write paths (hold, confirm, cancel), making the denormalization risk fully mitigated. A computed availability model (Model B) would require a multi-table join on every cache miss, breaking the <200ms latency target.
- We decided **`availability-service` merges into `venue-service`** because availability is a read view over slot data that venue-service already owns. A separate service would require either shared DB access (violates service isolation) or a synchronous HTTP hop on the hot read path. Venue-service now owns: venue CRUD, slot CRUD + auto-generation, availability reads (Redis cache + Postgres fallback), and cache key management. booking-service continues to own cache invalidation (DEL on hold/confirm/cancel).
- We confirmed the **proposed services are microservices** by the standard definition: each owns its own database, is independently deployable, and maps to a single bounded context. The architecture is intentionally microservices-first for learning purposes.
- We decided **booking-service → notification-service is async (SQS)** because the user's HTTP response does not depend on notification delivery; if notification-service is unavailable the booking operation must not fail. booking-service publishes `HoldExpiredEvent`, `BookingConfirmedEvent`, `BookingCancelledEvent` to SQS; notification-service consumes and dispatches.
- We decided **booking-service → venue-service uses sync HTTP for slot verification** (one call at hold creation to confirm slots exist and get venueId) and **shared Postgres cluster for slot.status writes** (Phase 0 pragmatic compromise). The slot.status UPDATE must be atomic with the holds INSERT — making it async would break the secondary Postgres safety gate for the ≤60s expiry lag window. True DB isolation is a Phase 1+ goal.
- We decided **cache invalidation (DEL slots:{venueId}:{date}) is a direct Redis write by booking-service** — not a service call to venue-service.
- We decided **user-service → booking-service has no runtime HTTP call** because booking-service validates JWTs locally via Spring Security, extracting userId and role from token claims. Account lifecycle events (deletion, suspension) are async Phase 1+.
- We decided **AWS Cloud Map for internal service discovery** because ECS Fargate natively integrates with it — tasks auto-register/deregister on start/stop, providing stable internal DNS (`http://venue-service.seatlock.local:8080`) without manual IP management or per-service internal ALBs.
- We decided **a single public-facing ALB with path-based listener rules** routes external traffic to four target groups: user-service (`/api/v1/auth/**`), venue-service (`/api/v1/venues/**`, `/api/v1/admin/**`), booking-service (`/api/v1/holds/**`, `/api/v1/bookings/**`). notification-service has no public endpoint. No API Gateway needed at 10k DAU.
- We decided **Service JWT for inter-service auth** (booking-service → venue-service). booking-service signs a short-lived JWT (`sub: booking-service`, `iss: seatlock-internal`) with a Vault-sourced shared secret. venue-service validates signature and `sub` claim. This covers non-user callers (expiry job), provides defense-in-depth beyond security groups, and is simpler than SigV4 while reusing existing Spring Security JWT infrastructure.

### Deep Dive Decisions (Section 6 — complete)

- We decided **reorder `POST /bookings` so Postgres commits before Redis DEL** because a crash after DEL but before Postgres commit leaves orphaned state with no recovery path; Postgres is the source of truth, Redis DEL is best-effort cleanup (OQ-19 D-6.A.1).
- We decided **add `DEL hold:{slotId}` to the cancellation flow** because a crash-induced stale Redis hold key blocks new holds on a genuinely available slot after cancellation; DEL on a missing key is a no-op so this is always safe (OQ-19 D-6.A.2).
- We decided **DEL + 5s TTL is the cache invalidation strategy** — explicit DEL on every slot status write is the fast path; 5s TTL bounds worst-case staleness from the write-after-read race; write-through and pub/sub are rejected (OQ-20 D-6.B.1).
- We decided **read-only degraded mode when Redis is unavailable** — `POST /holds` returns 503; `GET /venues/{venueId}/slots` continues via Postgres fallback; no Postgres `SELECT FOR UPDATE` fallback write path (OQ-21 D-6.C.1).
- We decided **fail fast on startup if Vault is unreachable** — container exits, ECS restarts with backoff; cached secrets on startup are rejected because they defeat secret rotation (OQ-22 D-6.D.1).
- We decided **hold in-memory secrets at runtime if Vault becomes unreachable** — degrade the health endpoint, alert via metrics, do not crash a running service over a transient Vault blip (OQ-22 D-6.D.2).

### Implementation Decisions (Phase 1 — Stages 1–2)

- We chose **`jvmArgs("-Dapi.version=1.44")`** over setting `DOCKER_API_VERSION` as an env var because Testcontainers 1.21.0 ships a shaded copy of docker-java whose `DefaultDockerClientConfig` reads the API version from the JVM system property `api.version`, not from the `DOCKER_API_VERSION` environment variable. Docker Desktop 4.60.1 enforces minimum API version 1.44; without this flag every Testcontainers request goes to `/v1.32/...` and gets HTTP 400. Added to every `integrationTest` task in all 4 service `build.gradle.kts` files.
- We chose **`Persistable<UUID>`** over `@GeneratedValue(strategy = UUID)` with a pre-initialized field because initializing `userId = UUID.randomUUID()` in the field declaration makes Hibernate treat the entity as existing (non-null ID → UPDATE instead of INSERT). Implementing `Persistable<UUID>` with `isNew = true` on construction and `@PostPersist/@PostLoad` to flip it correctly signals new vs. existing to Spring Data. This also lets unit tests use a real UUID without JPA.
- We chose **static initializer** over `@Testcontainers/@Container` for managing the PostgreSQL container in `AbstractIntegrationTest` because `@Testcontainers` stops the container at the end of each test CLASS. When multiple IT classes extend the base, the second class restarts the container on a new port while Spring's cached context still holds the old URL → `Connection refused`. A static initializer starts the container once for the JVM lifetime, all test classes share it, and Spring context caching works correctly.
- We chose **explicit `authenticationEntryPoint` returning 401** in `SecurityConfig` because Spring Security 6.x defaults to `Http403ForbiddenEntryPoint` (403) when neither formLogin nor httpBasic is configured. Without an explicit entry point, unauthenticated requests to protected endpoints return 403 instead of 401.
- We chose **permit `/error`** in `SecurityConfig` because Tomcat forwards unhandled exceptions to `/error`. Without permitting this path, the error response itself gets intercepted by Security → returns 401 instead of the actual error status (500, 409, etc.).
- We chose **`tcp://localhost:2375`** as the Docker transport for integration tests (via `DOCKER_HOST` Windows env var + `~/.gradle/gradle.properties dockerHost`) because TCP is enabled in Docker Desktop and confirmed working via curl. Named pipe (`docker_engine`) also connects but has the same API version constraint.
- We added `docs/BUGS.md` as a permanent bug/fix log. Critical bugs resolved during implementation are documented there with symptom, root cause, fix, and files changed.

---

## Vault Secret Inventory

| Secret | Used by |
|--------|---------|
| User JWT signing secret | user-service (sign), all services (verify) |
| Service JWT signing secret | booking-service (sign), venue-service (verify) |
| Postgres credentials (per service) | user-service, venue-service, booking-service |
| Redis connection credentials | venue-service, booking-service |
| AWS SQS credentials | booking-service (publish), notification-service (consume) |
| SMTP / SMS / push API keys | notification-service |

---

## Key Numbers

| Metric | Value |
|--------|-------|
| Daily active users (design target) | 10,000 |
| Registered users at launch | ~1,000 |
| Bookings per day | ~1,000 (~10% of DAU) |
| Peak concurrent users | 200 |
| Peak hold creation rate | ~50 holds/min |
| Hold duration | 30 minutes |
| Cancellation window | Up to 24h before reservation |
| p99 browse latency target | < 200ms |
| p99 hold latency target | < 500ms |
| Uptime SLA | 99.9% (~8.7h downtime/year) |
| Browse staleness tolerance | Up to 5 seconds |

---

## Confirmed Services

| Service | Responsibility |
|---------|---------------|
| user-service | Registration, login, JWT issuance, profile |
| venue-service | Venue CRUD, slot CRUD + auto-generation (admin), slot reads (users), availability reads — Redis cache (`slots:{venueId}:{date}`, TTL=5s) + Postgres fallback |
| booking-service | Hold creation, booking confirmation, cancellation, booking history, hold expiry job, Redis cache invalidation (DEL on hold/confirm/cancel), slot.status writes (Phase 0 shared Postgres) |
| notification-service | Email + in-app + SMS dispatch (SQS consumer) |
| ~~availability-service~~ | **Merged into venue-service** (OQ-15 resolved 2026-02-24) |
| ~~hold-service~~ | **Merged into booking-service** (decided Section 2) |

---

## Files Written So Far

> For a complete, navigable listing of every file see `docs/INDEX.md`.

**Navigation:**
| File | Purpose |
|------|---------|
| `docs/INDEX.md` | Master navigation index — find anything in 10 seconds |
| `docs/CODING_PLAN.md` | 16-stage implementation plan — current stage + exact operation sequences |
| `docs/PROJECT_PLAN.md` | Phase/milestone tracker |
| `docs/open-questions.md` | All design questions and resolutions |
| `docs/M0-phase0-complete.md` | Phase 0 sign-off — all decisions, numbers, compromises |

**Design (Phase 0 — complete):**
| File | Contents |
|------|----------|
| `docs/system-design/01-requirements.md` | Functional + non-functional requirements, out-of-scope |
| `docs/system-design/02-core-entities.md` | 5 entities: fields, state machines, ownership |
| `docs/system-design/03-api-interface.md` | All endpoints, request/response shapes, error codes |
| `docs/system-design/04-data-flow.md` | 5 flows, Postgres DDL (all tables), Redis schema |
| `docs/system-design/05-high-level-design.md` | Service boundaries, communication, ALB routing, inter-service auth |
| `docs/system-design/06-deep-dives.md` | Crash recovery, cache invalidation, Redis fallback, Vault startup |
| `docs/decisions/ADR-001.md` | Microservices architecture |
| `docs/decisions/ADR-002.md` | Redis SETNX as primary concurrency gate |
| `docs/decisions/ADR-003.md` | booking-service owns Hold lifecycle |
| `docs/decisions/ADR-004.md` | availability-service merged into venue-service |
| `docs/decisions/ADR-005.md` | Async SQS for booking → notification |
| `docs/decisions/ADR-006.md` | Shared Postgres cluster (Phase 0 compromise) |
| `docs/decisions/ADR-007.md` | Service JWT for inter-service auth |
| `docs/decisions/ADR-008.md` | Postgres-before-Redis-DEL operation order |
| `docs/diagrams/01-system-context.md` | System context — external actors and dependencies |
| `docs/diagrams/02-service-architecture.md` | Service architecture Mermaid diagram |
| `docs/diagrams/03-booking-flow-sequence.md` | Sequence diagrams: hold, confirm, expiry |
| `docs/diagrams/04-data-model-erd.md` | ERD — all 5 tables, PKs, FKs, indexes |
| `docs/diagrams/05-infrastructure.md` | AWS infrastructure topology |
| `docs/BUGS.md` | Critical bug/fix log — symptom, root cause, fix, files changed |

**Implementation (Phase 1 — Stage 1 complete):**
| File | Contents |
|------|----------|
| `{service}/build.gradle.kts` (×4) | Gradle build for each service — integrationTest source set, Testcontainers deps, `jvmArgs("-Dapi.version=1.44")` |
| `{service}/src/integrationTest/java/.../AbstractIntegrationTest.java` (×4) | Base class — `@SpringBootTest`, `@ActiveProfiles("test")`, shared Testcontainers setup |
| `{service}/src/integrationTest/java/.../*ApplicationIT.java` (×4) | Sample integration test — context loads + actuator health returns UP |
| `{service}/src/test/resources/application-test.yml` (×4) | Test profile — Testcontainers dynamic datasource properties |
| `.github/workflows/ci.yml` | CI workflow — runs `test` and `integrationTest` on every PR |

---

## Open Questions

Full list with resolution notes: `docs/open-questions.md`

**Sections 1–5 fully resolved — no blocking questions.**

**OQ-10 RESOLVED:** Frontend polling every 5s (matches 5s cache TTL). SSE/WebSockets deferred.
**OQ-11 PARTIAL (Phase 0):** `Idempotency-Key` required on `POST /holds` (becomes sessionId); `POST /bookings` deduplicates by sessionId. Full Redis-backed store is Phase 1.
**OQ-12 RESOLVED:** URL path prefix `/api/v1/`. Header versioning deferred.
**OQ-15 RESOLVED:** availability-service merged into venue-service (2026-02-24).
**OQ-16 RESOLVED:** booking→notification async (SQS); booking→venue sync HTTP + shared Postgres; user→booking no runtime call.
**OQ-17 RESOLVED:** AWS Cloud Map for internal service discovery.
**Q4 RESOLVED:** Single ALB with path-based listener rules.
**Q5 RESOLVED:** Service JWT for inter-service auth (booking-service → venue-service).

**All Sections 1–6 fully resolved — no blocking questions.**

**OQ-19 RESOLVED:** Reorder `POST /bookings` (Postgres before Redis DEL); add `DEL hold:{slotId}` to cancellation flow.
**OQ-20 RESOLVED:** DEL + 5s TTL sufficient. Write-through and pub/sub rejected.
**OQ-21 RESOLVED:** Read-only degraded mode when Redis unavailable. `POST /holds` → 503.
**OQ-22 RESOLVED:** Fail fast on startup. In-memory hold at runtime with health degradation.

---

## Phase 0 Compromises to Address in Phase 1+

| Compromise | Phase | Note |
|------------|-------|------|
| Shared Postgres cluster (booking-service writes slots.status) | Phase 1 | Extract slot_availability into booking-service's DB |
| Full Redis-backed idempotency store | Phase 1 | Currently in-process dedup only |
| Vault HA cluster | Phase 1+ | Currently single ECS task |
| Venue-timezone date filtering | Phase 1+ | Currently UTC only |
| Rate limiting numeric limits on POST /holds | Phase 1 | Endpoint identified; limits not set |
| Asymmetric JWT (RS256) for inter-service auth | Phase 2+ | Currently symmetric HMAC |
| Account lifecycle events (user deletion/suspension) | Phase 1+ | No user→booking async events yet |

---

## How to Continue in a New Session

> **New session workflow:** The user will feed you these four files at the start of every session:
> `docs/CONTEXT.md` (this file) · `docs/INDEX.md` · `docs/CODING_PLAN.md` · `docs/open-questions.md`
> You do not need to read any other files to get started — everything needed is in these four.

```
You are implementing SeatLock — a distributed reservation platform with
real-time availability and concurrency-safe booking.

You have been given four files: CONTEXT.md, INDEX.md, CODING_PLAN.md,
open-questions.md. That is your complete context. Do not ask to read
other files unless you need a specific detail during implementation.

== CURRENT STATUS ==
Phase 0 (system design): COMPLETE — all decisions are final, do not re-raise them.
Phase 1+ (implementation): find the first stage marked NOT STARTED in
CODING_PLAN.md Stage Overview table — that is where you begin.

== YOUR JOB ==
1. Find the current stage in CODING_PLAN.md (first NOT STARTED or IN PROGRESS).
2. Mark it IN PROGRESS in CODING_PLAN.md before you begin.
3. Implement exactly what the stage describes. Follow the exact operation
   sequences in booking-service stages (7–10) — they are non-negotiable.
4. When the stage's acceptance criteria are all met, mark it COMPLETE in
   CODING_PLAN.md and update the Stage Overview table status line.
5. At session end: update CONTEXT.md "Current Phase & Status" table if the
   implementation phase changed. Note any new implementation decisions made.

== NON-NEGOTIABLE RULES (do not question, do not deviate) ==
Tech stack:     Java 21, Spring Boot 3.x, Spring Security, Spring Data JPA,
                Spring Data Redis, Flyway, JUnit 5, Testcontainers
                React 18 + TypeScript + TanStack Query v5 (frontend)
API contract:   All endpoints exactly as in docs/system-design/03-api-interface.md
Operation order POST /bookings: Postgres commits BEFORE Redis DEL (ADR-008)
                Violation of this order introduces an unrecoverable crash window.
Concurrency:    Redis SETNX is the hold gate. AND status='AVAILABLE' on Postgres
                UPDATE is the secondary gate. Both must be present (ADR-002).
Expiry job:     SELECT FOR UPDATE SKIP LOCKED, batch ≤500, retry 3× then halve.
Cancellation:   DEL hold:{slotId} must fire (no-op safe) to clear stale crash keys.
Tests:          Every stage requires both unit tests AND integration tests
                (Testcontainers). No stage is complete without passing tests.
Redis failure:  POST /holds returns 503. GET /venues/slots falls back to Postgres.

== WHAT IS IN YOUR CONTEXT FILES ==
CONTEXT.md      — every architecture decision made, key numbers, services, Vault secrets
INDEX.md        — navigation map for all files; quick-ref for error codes, Redis keys,
                  tech stack, key numbers, compromises to fix in Phase 1+
CODING_PLAN.md  — 16 stages with acceptance criteria and exact operation sequences
open-questions.md — all design questions resolved (reference only — nothing open)
```
