# SeatLock — Project Index

> Use this file to find anything in the project within 10 seconds.
> Updated when new files are added.

---

## Project Status

| Item | Status |
|------|--------|
| Phase 0 — System Design | ✅ COMPLETE |
| Phase 1+ — Implementation | IN PROGRESS — Stages 1–10 complete, Stage 11 next |
| Current implementation stage | Stage 11 — notification-service |

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
| Look up a bug fix or environment workaround | `docs/BUGS.md` |
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
| `docs/BUGS.md` | Critical bug/fix log — symptom, root cause, fix, files changed |

---

## Implemented API Endpoints (live)

| Method | Path | Service | Status | Stage |
|--------|------|---------|--------|-------|
| POST | `/api/v1/auth/register` | user-service | 201 Created / 409 EMAIL_ALREADY_EXISTS | 3 |
| POST | `/api/v1/auth/login` | user-service | 200 OK / 401 INVALID_CREDENTIALS | 3 |
| GET | `/api/v1/venues` | venue-service | 200 OK (public) | 4 |
| POST | `/api/v1/admin/venues` | venue-service | 201 Created / 403 (non-ADMIN) | 4 |
| PATCH | `/api/v1/admin/venues/{id}/status` | venue-service | 200 OK / 403 (non-ADMIN) | 4 |
| GET | `/api/v1/venues/{venueId}/slots` | venue-service | 200 OK / 404 VENUE_NOT_FOUND (public) | 4 |
| POST | `/api/v1/admin/venues/{venueId}/slots/generate` | venue-service | 201 Created / 403 (non-ADMIN) | 4 |
| GET | `/api/v1/internal/slots?ids=...` | venue-service | 200 OK / 401 (no/bad service JWT) / 404 SLOT_NOT_FOUND | 6 |
| POST | `/api/v1/holds` | booking-service | 200 OK / 400 MISSING_IDEMPOTENCY_KEY / 404 SLOT_NOT_FOUND / 409 SLOT_NOT_AVAILABLE / 503 SERVICE_UNAVAILABLE | 7 |
| POST | `/api/v1/bookings` | booking-service | 201 Created / 404 SESSION_NOT_FOUND / 403 FORBIDDEN / 409 HOLD_EXPIRED / 409 HOLD_MISMATCH | 8 |
| POST | `/api/v1/bookings/{confirmationNumber}/cancel` | booking-service | 200 OK / 404 BOOKING_NOT_FOUND / 403 FORBIDDEN / 409 CANCELLATION_WINDOW_CLOSED | 10 |
| GET | `/api/v1/bookings` | booking-service | 200 OK (user history, grouped by session) | 10 |
| GET | `/api/v1/admin/venues/{venueId}/bookings` | booking-service | 200 OK / 403 (non-ADMIN) | 10 |

---

## Implementation Files

### Stage 3 — user-service: Auth

| File | Purpose |
|------|---------|
| `user-service/src/main/resources/db/migration/V1__create_users.sql` | Flyway DDL: `users` table |
| `user-service/src/main/java/com/seatlock/user/domain/User.java` | JPA entity; `Persistable<UUID>` for client-side UUID |
| `user-service/src/main/java/com/seatlock/user/repository/UserRepository.java` | Spring Data JPA repo |
| `user-service/src/main/java/com/seatlock/user/dto/RegisterRequest.java` | Validated registration request record |
| `user-service/src/main/java/com/seatlock/user/dto/RegisterResponse.java` | Registration response record |
| `user-service/src/main/java/com/seatlock/user/dto/LoginRequest.java` | Login request record |
| `user-service/src/main/java/com/seatlock/user/dto/LoginResponse.java` | Login response record (token + expiresAt) |
| `user-service/src/main/java/com/seatlock/user/security/JwtService.java` | JWT issuance and parsing (HMAC-SHA256, 24h TTL) |
| `user-service/src/main/java/com/seatlock/user/security/JwtAuthenticationFilter.java` | Bearer token extraction → SecurityContextHolder |
| `user-service/src/main/java/com/seatlock/user/security/SecurityConfig.java` | Stateless security chain; explicit 401 entry point |
| `user-service/src/main/java/com/seatlock/user/service/UserRegistrationService.java` | Register: email dedup, password hash, save |
| `user-service/src/main/java/com/seatlock/user/service/AuthenticationService.java` | Login: lookup, password verify, issue token |
| `user-service/src/main/java/com/seatlock/user/controller/AuthController.java` | `POST /api/v1/auth/register` + `POST /api/v1/auth/login` |
| `user-service/src/main/java/com/seatlock/user/exception/GlobalExceptionHandler.java` | Maps domain exceptions → HTTP responses |
| `user-service/src/test/java/com/seatlock/user/service/UserRegistrationServiceTest.java` | Unit tests: happy path, duplicate email |
| `user-service/src/test/java/com/seatlock/user/security/JwtServiceTest.java` | Unit tests: token issuance, claims parsing |
| `user-service/src/integrationTest/java/com/seatlock/user/controller/AuthControllerIT.java` | 6 ITs: register, duplicate, login, bad pass, JWT auth, claims |

### Stage 4 — venue-service: Venue + Slot CRUD

| File | Purpose |
|------|---------|
| `venue-service/src/main/resources/db/migration/V1__create_venues.sql` | Flyway DDL: `venues` table |
| `venue-service/src/main/resources/db/migration/V2__create_slots.sql` | Flyway DDL: `slots` table + indexes |
| `venue-service/src/main/java/com/seatlock/venue/domain/Venue.java` | JPA entity; `Persistable<UUID>`; soft-delete via `VenueStatus` |
| `venue-service/src/main/java/com/seatlock/venue/domain/Slot.java` | JPA entity; `Persistable<UUID>`; raw `venueId` UUID FK column |
| `venue-service/src/main/java/com/seatlock/venue/domain/VenueStatus.java` | Enum: `ACTIVE`, `INACTIVE` |
| `venue-service/src/main/java/com/seatlock/venue/domain/SlotStatus.java` | Enum: `AVAILABLE`, `HELD`, `BOOKED` |
| `venue-service/src/main/java/com/seatlock/venue/repository/VenueRepository.java` | `findByStatus(VenueStatus)` |
| `venue-service/src/main/java/com/seatlock/venue/repository/SlotRepository.java` | `findByVenueIdAndDay`, `findByVenueIdAndStartTimeBetween`, `findByVenueIdOrderByStartTimeAsc` |
| `venue-service/src/main/java/com/seatlock/venue/dto/CreateVenueRequest.java` | Validated create-venue request |
| `venue-service/src/main/java/com/seatlock/venue/dto/UpdateVenueStatusRequest.java` | Validated status patch request |
| `venue-service/src/main/java/com/seatlock/venue/dto/GenerateSlotsRequest.java` | `fromDate`/`toDate` for slot generation |
| `venue-service/src/main/java/com/seatlock/venue/dto/VenueResponse.java` | Venue API response record |
| `venue-service/src/main/java/com/seatlock/venue/dto/SlotResponse.java` | Slot API response record (includes derived `endTime`) |
| `venue-service/src/main/java/com/seatlock/venue/security/JwtConfig.java` | `@Bean JwtUtils` — separate config to avoid circular dep |
| `venue-service/src/main/java/com/seatlock/venue/security/JwtAuthenticationFilter.java` | Bearer token → SecurityContextHolder via `JwtUtils` from common |
| `venue-service/src/main/java/com/seatlock/venue/security/SecurityConfig.java` | Stateless chain; GETs public; `/api/v1/admin/**` → ADMIN role |
| `venue-service/src/main/java/com/seatlock/venue/service/SlotGenerationService.java` | Mon–Fri 09:00–17:00 UTC, 60-min blocks, duplicate-safe bulk insert |
| `venue-service/src/main/java/com/seatlock/venue/service/VenueService.java` | All venue+slot business logic; status filter in app layer |
| `venue-service/src/main/java/com/seatlock/venue/controller/VenueController.java` | `GET /api/v1/venues`, `GET /api/v1/venues/{id}/slots` |
| `venue-service/src/main/java/com/seatlock/venue/controller/AdminVenueController.java` | `POST /api/v1/admin/venues`, `PATCH /{id}/status`, `POST /{id}/slots/generate` |
| `venue-service/src/main/java/com/seatlock/venue/exception/VenueNotFoundException.java` | Domain exception → 404 VENUE_NOT_FOUND |
| `venue-service/src/main/java/com/seatlock/venue/exception/GlobalExceptionHandler.java` | Maps domain exceptions → HTTP responses |
| `venue-service/src/test/java/com/seatlock/venue/service/SlotGenerationServiceTest.java` | 4 unit tests: weekdays only, correct times, skip duplicates, weekend empty |
| `venue-service/src/integrationTest/java/com/seatlock/venue/controller/VenueControllerIT.java` | 11 ITs: CRUD, auth, generate, date filter, status filter, 404s, endTime |

### Stage 5 — venue-service: Availability Cache

| File | Purpose |
|------|---------|
| `venue-service/src/main/java/com/seatlock/venue/cache/SlotCacheService.java` | `buildKey`, `get`, `put` — `StringRedisTemplate` + `ObjectMapper`; swallows serialization errors |
| `venue-service/src/main/java/com/seatlock/venue/service/VenueService.java` | Updated: cache-first path for date-scoped queries; `applyStatusFilter` extracted as helper |
| `venue-service/src/main/resources/application.yml` | Added `spring.data.redis.host/port`, `seatlock.cache.slots-ttl-seconds: 5` |
| `venue-service/src/test/resources/application-test.yml` | Added `seatlock.cache.slots-ttl-seconds: 1` for TTL expiry test |
| `venue-service/src/integrationTest/java/com/seatlock/venue/AbstractIntegrationTest.java` | Added Redis `GenericContainer` in static initializer; wired `spring.data.redis.host/port` via `@DynamicPropertySource` |
| `venue-service/src/test/java/com/seatlock/venue/cache/SlotCacheServiceTest.java` | 5 unit tests: key format, cache miss, round-trip field preservation, TTL setting, corrupt JSON |
| `venue-service/src/integrationTest/java/com/seatlock/venue/cache/SlotCacheIT.java` | 5 ITs: key populated, key present on second request + data consistent, TTL expiry, status filter, endTime |
| `venue-service/build.gradle.kts` | Added `spring-boot-starter-data-redis` dependency |

### Stage 6 — booking-service: Foundation + Service JWT

| File | Purpose |
|------|---------|
| `booking-service/src/main/resources/db/migration/V1__create_holds.sql` | Flyway DDL: `holds` table (cross-service FKs to users + slots) |
| `booking-service/src/main/resources/db/migration/V2__create_bookings.sql` | Flyway DDL: `bookings` table (FK to holds) |
| `booking-service/src/test/resources/db/migration/V0__create_stub_tables.sql` | Test-only: minimal stub `users` + `slots` for FK satisfaction in Testcontainers |
| `booking-service/src/main/java/com/seatlock/booking/domain/HoldStatus.java` | Enum: `ACTIVE`, `CONFIRMED`, `EXPIRED`, `RELEASED` |
| `booking-service/src/main/java/com/seatlock/booking/domain/BookingStatus.java` | Enum: `CONFIRMED`, `CANCELLED` |
| `booking-service/src/main/java/com/seatlock/booking/domain/Hold.java` | JPA entity; `Persistable<UUID>` |
| `booking-service/src/main/java/com/seatlock/booking/domain/Booking.java` | JPA entity; `Persistable<UUID>` |
| `booking-service/src/main/java/com/seatlock/booking/repository/HoldRepository.java` | Spring Data JPA repo |
| `booking-service/src/main/java/com/seatlock/booking/repository/BookingRepository.java` | Spring Data JPA repo |
| `booking-service/src/main/java/com/seatlock/booking/security/ServiceJwtService.java` | Signs short-lived JWTs (`sub: booking-service`, `iss: seatlock-internal`, 5-min TTL) |
| `booking-service/src/main/java/com/seatlock/booking/security/JwtConfig.java` | `@Bean JwtUtils` for user JWT validation (separate config avoids circular dep) |
| `booking-service/src/main/java/com/seatlock/booking/security/JwtAuthenticationFilter.java` | User Bearer token → SecurityContextHolder |
| `booking-service/src/main/java/com/seatlock/booking/security/SecurityConfig.java` | Stateless chain; health + error public; all else requires auth |
| `booking-service/src/main/java/com/seatlock/booking/config/VenueServiceClientConfig.java` | `RestClient` bean with per-request service JWT interceptor |
| `booking-service/src/main/resources/application.yml` | Added service-jwt config, user JWT secret, Redis, Jackson, venue-service base-url |
| `booking-service/src/test/resources/application-test.yml` | Test overrides for JWT secrets + service-jwt config |
| `booking-service/build.gradle.kts` | Added spring-security, jjwt-api, validation, spring-data-redis deps |
| `booking-service/src/test/java/com/seatlock/booking/security/ServiceJwtServiceTest.java` | 4 unit tests: subject, issuer, expiry, signature |
| `venue-service/src/main/java/com/seatlock/venue/security/ServiceJwtAuthenticationFilter.java` | Validates service JWTs on `/api/v1/internal/**`; 401 on any failure |
| `venue-service/src/main/java/com/seatlock/venue/security/JwtAuthenticationFilter.java` | Added `shouldNotFilter` to skip `/api/v1/internal/**` |
| `venue-service/src/main/java/com/seatlock/venue/security/SecurityConfig.java` | Updated: `ROLE_SERVICE` required for `/internal/**`; added `ServiceJwtAuthenticationFilter` |
| `venue-service/src/main/java/com/seatlock/venue/dto/InternalSlotResponse.java` | `{slotId, venueId, startTime, status}` response record |
| `venue-service/src/main/java/com/seatlock/venue/controller/InternalSlotController.java` | `GET /api/v1/internal/slots?ids=...`; 404 if any ID missing |
| `venue-service/src/main/java/com/seatlock/venue/exception/GlobalExceptionHandler.java` | Added `SlotNotFoundException` handler → 404 SLOT_NOT_FOUND |
| `venue-service/src/main/resources/application.yml` | Added `seatlock.service-jwt.secret` |
| `venue-service/src/test/resources/application-test.yml` | Added `seatlock.service-jwt.secret` for tests |
| `venue-service/src/integrationTest/java/com/seatlock/venue/controller/InternalSlotControllerIT.java` | 5 ITs: valid JWT 200, no JWT 401, user JWT 401, expired JWT 401, unknown slot 404 |

### Stage 7 — booking-service: Hold Creation

| File | Purpose |
|------|---------|
| `booking-service/src/main/java/com/seatlock/booking/dto/HoldRequest.java` | Validated request: `slotIds` list (not empty, elements not null) |
| `booking-service/src/main/java/com/seatlock/booking/dto/HoldResponse.java` | Response: `sessionId`, `expiresAt`, `holds[]` (nested `HoldItemResponse`) |
| `booking-service/src/main/java/com/seatlock/booking/client/InternalSlotResponse.java` | `{slotId, venueId, startTime, status}` record from venue-service |
| `booking-service/src/main/java/com/seatlock/booking/client/SlotVerificationClient.java` | `GET /api/v1/internal/slots?ids=...`; 404 → `SlotNotFoundException` |
| `booking-service/src/main/java/com/seatlock/booking/redis/HoldPayload.java` | `{holdId, userId, sessionId, expiresAt}` record serialized to Redis |
| `booking-service/src/main/java/com/seatlock/booking/redis/RedisHoldRepository.java` | `setnx` (NX EX 1800), `del`, `deleteSlotCache`; wraps `StringRedisTemplate` |
| `booking-service/src/main/java/com/seatlock/booking/exception/MissingIdempotencyKeyException.java` | → 400 MISSING_IDEMPOTENCY_KEY |
| `booking-service/src/main/java/com/seatlock/booking/exception/SlotNotFoundException.java` | → 404 SLOT_NOT_FOUND |
| `booking-service/src/main/java/com/seatlock/booking/exception/SlotNotAvailableException.java` | → 409 SLOT_NOT_AVAILABLE + `unavailableSlotIds` list |
| `booking-service/src/main/java/com/seatlock/booking/exception/RedisUnavailableException.java` | → 503 SERVICE_UNAVAILABLE |
| `booking-service/src/main/java/com/seatlock/booking/exception/GlobalExceptionHandler.java` | `@RestControllerAdvice`; maps all domain exceptions to error codes |
| `booking-service/src/main/java/com/seatlock/booking/service/HoldService.java` | All 8 steps; `TransactionTemplate` scopes Postgres phase only |
| `booking-service/src/main/java/com/seatlock/booking/controller/HoldController.java` | `POST /api/v1/holds`; validates Idempotency-Key header |
| `booking-service/src/main/java/com/seatlock/booking/security/JwtAuthenticationFilter.java` | Updated: stores `userId` claim in `auth.setDetails()` |
| `booking-service/src/main/java/com/seatlock/booking/repository/HoldRepository.java` | Added `findBySessionIdAndStatus(UUID, HoldStatus)` |
| `booking-service/src/test/resources/db/migration/V0__create_stub_tables.sql` | Updated: added `status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE'` to stub `slots` table |
| `booking-service/src/test/java/com/seatlock/booking/service/HoldServiceTest.java` | 4 unit tests: idempotency check, SETNX rollback, PG row-count mismatch, happy path |
| `booking-service/src/integrationTest/java/com/seatlock/booking/controller/HoldControllerIT.java` | 5 ITs: happy path (Redis TTL + PG state), 400, 409 SETNX fail, 409 PG mismatch, idempotent replay, concurrency (10 threads → 1 wins) |

### Stage 8 — booking-service: Booking Confirmation

| File | Purpose |
|------|---------|
| `booking-service/src/main/java/com/seatlock/booking/dto/BookingRequest.java` | Validated request: `sessionId` UUID |
| `booking-service/src/main/java/com/seatlock/booking/dto/BookingResponse.java` | Response: `confirmationNumber`, `sessionId`, `bookings[]` |
| `booking-service/src/main/java/com/seatlock/booking/event/BookingConfirmedEvent.java` | Event record for SQS publish (Stage 11) |
| `booking-service/src/main/java/com/seatlock/booking/event/BookingEventPublisher.java` | Publisher interface |
| `booking-service/src/main/java/com/seatlock/booking/event/NoOpBookingEventPublisher.java` | No-op stub; replaced with SQS in Stage 11 |
| `booking-service/src/main/java/com/seatlock/booking/service/ConfirmationNumberGenerator.java` | Generates `SL-YYYYMMDD-XXXX` |
| `booking-service/src/main/java/com/seatlock/booking/service/BookingService.java` | Steps 0–8; `TransactionTemplate` for Postgres phase; Postgres commits BEFORE Redis DEL |
| `booking-service/src/main/java/com/seatlock/booking/controller/BookingController.java` | `POST /api/v1/bookings` → 201 |
| `booking-service/src/main/java/com/seatlock/booking/exception/SessionNotFoundException.java` | → 404 SESSION_NOT_FOUND |
| `booking-service/src/main/java/com/seatlock/booking/exception/HoldExpiredException.java` | → 409 HOLD_EXPIRED |
| `booking-service/src/main/java/com/seatlock/booking/exception/HoldMismatchException.java` | → 409 HOLD_MISMATCH |
| `booking-service/src/main/java/com/seatlock/booking/exception/ForbiddenException.java` | → 403 FORBIDDEN |
| `booking-service/src/main/java/com/seatlock/booking/redis/RedisHoldRepository.java` | Added `getHold(slotId)` → `Optional<HoldPayload>` |
| `booking-service/src/main/java/com/seatlock/booking/repository/BookingRepository.java` | Added `findBySessionIdAndStatus` |
| `booking-service/src/main/java/com/seatlock/booking/repository/HoldRepository.java` | Added `updateStatusBySessionId` (`@Modifying` JPQL) |
| `booking-service/src/main/java/com/seatlock/booking/exception/GlobalExceptionHandler.java` | Added handlers for all 4 new exceptions |
| `booking-service/src/test/java/com/seatlock/booking/service/BookingServiceTest.java` | 6 unit tests: idempotency, session not found, forbidden, expired, mismatch, happy path |
| `booking-service/src/integrationTest/java/com/seatlock/booking/controller/BookingControllerIT.java` | 5 ITs: happy path, HOLD_EXPIRED, HOLD_MISMATCH, idempotent replay, crash-recovery simulation |

---

## Implementation Plan

Full detail in `docs/CODING_PLAN.md`. Summary below.

| Stage | Name | Key output | Status |
|-------|------|-----------|--------|
| 1 | Project Scaffolding | Multi-module Gradle, Docker Compose, GitHub Actions CI | COMPLETE |
| 2 | Testing Infrastructure | JUnit 5, Testcontainers, integration test base classes | COMPLETE |
| 3 | user-service: Auth | Register, login, JWT issuance, Spring Security filter | COMPLETE |
| 4 | venue-service: Venue + Slot CRUD | Venue/slot CRUD, slot auto-generation, admin endpoints | COMPLETE |
| 5 | venue-service: Availability Cache | Redis cache for slots, cache miss → Postgres fallback | COMPLETE |
| 6 | booking-service: Foundation + Service JWT | booking-service setup, Flyway migrations, Service JWT handshake | COMPLETE |
| 7 | booking-service: Hold Creation | POST /holds — SETNX gate, all-or-nothing, Postgres writes | COMPLETE |
| 8 | booking-service: Booking Confirmation | POST /bookings — crash-safe order, idempotency, confirmationNumber | COMPLETE |
| 9 | booking-service: Hold Expiry Job | @Scheduled 60s, SKIP LOCKED, retry + batch halving | COMPLETE |
| 10 | booking-service: Cancellation + History | POST cancel, GET history, admin view, stale key cleanup | COMPLETE |
| 11 | notification-service | SQS consumer, email/SMS dispatch, ElasticMQ in Docker Compose | NOT STARTED |
| 12 | Resilience | Redis 503 degraded mode, Vault fail-fast, circuit breaker, retries | NOT STARTED |
| 13 | Observability | Actuator health, Prometheus metrics, Grafana dashboard | NOT STARTED |
| 14 | Frontend: Auth + Browse | React, login/register, venue browse, slot polling (5s) | NOT STARTED |
| 15 | Frontend: Booking Flows | Hold confirm, cancel, history, domain error messages | NOT STARTED |
| 16 | Infrastructure (AWS) | Terraform ECS+RDS+Redis+ALB, GitHub Actions deploy pipeline | NOT STARTED |

### Stage 10 — booking-service: Cancellation + History

| File | Purpose |
|------|---------|
| `booking-service/src/main/java/com/seatlock/booking/event/BookingCancelledEvent.java` | Event record: `{confirmationNumber, sessionId, userId, cancelledSlotIds, timestamp}` |
| `booking-service/src/main/java/com/seatlock/booking/exception/BookingNotFoundException.java` | → 404 BOOKING_NOT_FOUND |
| `booking-service/src/main/java/com/seatlock/booking/exception/CancellationWindowClosedException.java` | → 409 CANCELLATION_WINDOW_CLOSED |
| `booking-service/src/main/java/com/seatlock/booking/dto/CancelResponse.java` | Cancel response: `{confirmationNumber, cancelledAt, bookings[]}` |
| `booking-service/src/main/java/com/seatlock/booking/dto/BookingHistoryResponse.java` | History response: sessions grouped by confirmationNumber |
| `booking-service/src/main/java/com/seatlock/booking/dto/AdminBookingResponse.java` | Admin view: flat list of confirmed bookings for a venue |
| `booking-service/src/main/java/com/seatlock/booking/service/CancellationService.java` | 8-step cancel; `getHistory`; `getAdminBookings`; ADR-008 stale key DEL |
| `booking-service/src/main/java/com/seatlock/booking/controller/BookingController.java` | Added `POST /{cn}/cancel` + `GET /bookings` (history) |
| `booking-service/src/main/java/com/seatlock/booking/controller/AdminBookingController.java` | `GET /api/v1/admin/venues/{venueId}/bookings` (ADMIN role) |
| `booking-service/src/main/java/com/seatlock/booking/event/BookingEventPublisher.java` | Added `publishBookingCancelled` method |
| `booking-service/src/main/java/com/seatlock/booking/event/NoOpBookingEventPublisher.java` | No-op stub for `publishBookingCancelled` |
| `booking-service/src/main/java/com/seatlock/booking/exception/GlobalExceptionHandler.java` | Added BOOKING_NOT_FOUND + CANCELLATION_WINDOW_CLOSED handlers |
| `booking-service/src/main/java/com/seatlock/booking/security/SecurityConfig.java` | Added `/api/v1/admin/**` → ADMIN role check |
| `booking-service/src/test/resources/db/migration/V0__create_stub_tables.sql` | Extended: nullable `venue_id`/`start_time` on slots; added venues stub table |
| `booking-service/src/integrationTest/java/com/seatlock/booking/controller/HoldControllerIT.java` | Fixed setUp delete order: bookings → holds (FK-safe) |
| `booking-service/src/test/java/com/seatlock/booking/service/CancellationServiceTest.java` | 5 unit tests: not found, forbidden, convergence, window checks, happy path |
| `booking-service/src/integrationTest/java/com/seatlock/booking/controller/CancellationControllerIT.java` | 6 ITs: cancel happy path (ADR-008 DEL), idempotent, 409 window, GET history, admin 200/403 |

### Stage 9 — booking-service: Hold Expiry Job

| File | Purpose |
|------|---------|
| `booking-service/src/main/java/com/seatlock/booking/BookingServiceApplication.java` | Added `@EnableScheduling` |
| `booking-service/src/main/java/com/seatlock/booking/event/HoldExpiredEvent.java` | Event record: `{sessionId, userId, expiredSlotIds, timestamp}` — one per session |
| `booking-service/src/main/java/com/seatlock/booking/event/BookingEventPublisher.java` | Added `publishHoldExpired(HoldExpiredEvent)` to interface |
| `booking-service/src/main/java/com/seatlock/booking/event/NoOpBookingEventPublisher.java` | No-op stub for `publishHoldExpired`; replaced with SQS in Stage 11 |
| `booking-service/src/main/java/com/seatlock/booking/service/HoldExpiryJob.java` | `@Scheduled` job: SELECT FOR UPDATE SKIP LOCKED; `AND status='ACTIVE'/'HELD'` guards; retry 3× with exponential backoff; halve batch on exhaustion; NO Redis DEL (TTL handles cleanup); events grouped by sessionId |
| `booking-service/src/main/resources/application.yml` | Added `seatlock.expiry.*`: `batch-size: 500`, `max-retries: 3`, `retry-backoff-base-ms: 500`, `interval-ms: 60000`, `initial-delay-ms: 10000` |
| `booking-service/src/test/resources/application-test.yml` | Added test expiry overrides: `initial-delay-ms: 3600000`, `interval-ms: 3600000`, `retry-backoff-base-ms: 0` |
| `booking-service/src/test/java/com/seatlock/booking/service/HoldExpiryJobTest.java` | 5 unit tests: no holds, happy path (event grouped by session), multi-hold same session, retry once, max retries + batch halving |
| `booking-service/src/integrationTest/java/com/seatlock/booking/service/HoldExpiryJobIT.java` | 3 ITs: expired hold → EXPIRED + slot AVAILABLE; non-expired → untouched; 10-hold concurrent SKIP LOCKED |

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
