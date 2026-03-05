# SeatLock — Session Log

> Most recent session at the top.
> Each entry records what was done, decisions made, and where to continue.

---

## Session 2026-03-04 — Stage 10: Cancellation + History

**Phase:** 1 — Foundation
**Started at:** Stage 10 (first NOT STARTED stage)
**Ended at:** Stage 10 COMPLETE. Stage 11 (notification-service) is next.

### What Was Accomplished

**Stage 10 fully implemented** — all 8 steps of the cancellation sequence, all three endpoints, all tests green (24 unit tests, 21 integration tests):

**`POST /api/v1/bookings/{confirmationNumber}/cancel`** (8-step sequence):
- Step 1: JdbcTemplate JOIN query (bookings ⋈ slots) — no status filter
- Step 2: authorize — all booking.userId must match JWT userId
- Step 3: convergence check — CONFIRMED set empty → 200 with current state (no writes)
- Step 4: 24h window check on CONFIRMED items — any violation → 409
- Step 5: Postgres transaction — bookings CANCELLED + holds RELEASED + slots AVAILABLE
- Step 6: Redis cleanup — `DEL hold:{slotId}` (ADR-008 stale key protection) + `DEL slots:{venueId}:{date}`
- Step 7: `BookingCancelledEvent` published (no-op stub until Stage 11)
- Step 8: response built from in-memory state (no extra DB round-trip)

**`GET /api/v1/bookings`** — user booking history, LEFT JOIN venues, grouped by `confirmationNumber` in application layer via `LinkedHashMap`, ordered newest first.

**`GET /api/v1/admin/venues/{venueId}/bookings`** — admin view of CONFIRMED bookings; optional `?date=` (UTC) filter; ADMIN role enforced by `SecurityConfig`.

**New service/controller split:** `CancellationService` handles all read + cancel operations; `BookingController` extended with two new endpoints; `AdminBookingController` is a new class.

### Bugs Fixed

- **HoldControllerIT FK delete order** — `CancellationControllerIT` leaves confirmed bookings in the shared Testcontainers DB; `HoldControllerIT.setUp()` was deleting from `holds` before `bookings`, violating the FK constraint. Fixed by prepending `DELETE FROM bookings` → FK-safe order: bookings → holds → slots → users.

### Design Deviations from Original Spec

None. Implementation follows the exact operation sequence in `CODING_PLAN.md` Stage 10.

### Gotchas / Surprises

- **JDBC type safety for TIMESTAMPTZ:** `queryForList()` returns `Object` from the column map; the type is driver-version-dependent (`Timestamp` vs `OffsetDateTime`). Switched `getHistory` to a typed `RowMapper` using `rs.getTimestamp()` which consistently returns `java.sql.Timestamp`.
- **V0 stub extension required:** The test stub `V0__create_stub_tables.sql` needed nullable `venue_id` and `start_time` added to `slots`, plus a new `venues` table for the `getHistory` LEFT JOIN. All existing tests still pass since those columns are nullable.
- **`CancellationService.BookingWithSlot` as package-private record:** Declared without access modifier inside the service so `CancellationServiceTest` (same package) can construct instances for unit test stubs. Java member records are implicitly static; package-private access is the right scope.
- **CODING_PLAN.md had stale statuses:** Stages 7 and 8 were still marked `NOT STARTED` in the overview table (carried over from old session). Fixed alongside Stage 10 update.

### What the Next Session Should Do First

Read Stage 11 in `CODING_PLAN.md`. Stage 11 = notification-service: SQS consumer (ElasticMQ in Docker Compose), `BookingConfirmedEvent` + `BookingCancelledEvent` + `HoldExpiredEvent` handling, email/SMS dispatch stubs. Replace the `NoOpBookingEventPublisher` in booking-service with a real SQS publisher.

---

## Session 2026-03-03 — Stages 8 + 9: Booking Confirmation + Hold Expiry Job

**Phase:** 1 — Foundation
**Started at:** Stage 8 — booking-service: Booking Confirmation (continuing from Stage 7)
**Ended at:** Stage 9 COMPLETE. Stage 10 is next.

### What Was Accomplished

**Stage 8: `POST /api/v1/bookings` fully implemented** — all 8 steps of the spec operation sequence, Postgres-before-Redis-DEL (ADR-008):

- `BookingController` → `BookingService.confirmBooking(sessionId, userId)`
- Step 0: idempotency check (`findBySessionIdAndStatus(CONFIRMED)`)
- Steps 1–3: load active holds → authorize user → verify Redis keys match
- Step 4: generate confirmation number (`SL-YYYYMMDD-XXXX`)
- Step 5: Postgres transaction via `TransactionTemplate` — INSERT bookings, UPDATE holds to CONFIRMED, UPDATE slots to BOOKED
- Step 6: Redis DEL hold keys AFTER commit (ADR-008 order enforced)
- Step 7: publish `BookingConfirmedEvent` (no-op stub)
- `ConfirmationNumberGenerator`: UTC date + 4-digit random
- 4 new exception classes: `SessionNotFoundException`, `HoldExpiredException`, `HoldMismatchException`, `ForbiddenException`
- `GlobalExceptionHandler` updated with 4 new handlers
- 6 unit tests (`BookingServiceTest`): idempotency, session not found, forbidden, hold expired, hold mismatch, happy path
- 5 integration tests (`BookingControllerIT`): happy path + DB assertions, expired hold 409, mismatch 409, idempotent replay, crash-recovery simulation

**Stage 9: Hold Expiry Job implemented** — `@Scheduled` background job using SKIP LOCKED:

- `HoldExpiryJob` Spring `@Component` with `@Scheduled(initialDelay, fixedDelay)`
- SELECT FOR UPDATE SKIP LOCKED — safe across multiple service instances
- `AND status = 'ACTIVE'` guard on holds UPDATE (belt-and-suspenders)
- `AND status = 'HELD'` guard on slots UPDATE (prevents resetting a BOOKED slot)
- NO Redis operation — TTL fires automatically; explicit DEL would signal wrong intent
- Retry on `PessimisticLockingFailureException`: 3 retries with exponential backoff (`backoff * 2^attempt`), then halve batch size
- `HoldExpiredEvent` grouped by sessionId: one event per session with list of expired slotIds
- `@EnableScheduling` added to `BookingServiceApplication`
- Unit tests: 5 tests — no holds, happy path, multi-hold same session grouped, retry once, max retries + batch halving
- Integration tests: 3 tests — expired → EXPIRED + AVAILABLE, non-expired → untouched, 10-hold concurrent SKIP LOCKED test

### Decisions Made This Session

- `TransactionTemplate` (not `@Transactional`) for `HoldExpiryJob` — same pattern as `HoldService`/`BookingService`; keeps Redis/event operations outside DB transaction
- NO Redis DEL in expiry job — TTL on `hold:{slotId}` expires at the same time as the hold; explicit DEL signals wrong intent and is a no-op anyway
- `HoldExpiredEvent` grouped by sessionId — spec says one event per session with list of `expiredSlotIds`; per-hold events would require the consumer to reassemble them
- `initial-delay-ms: 3600000` in test profile — `fixedDelay` fires immediately on startup without `initialDelay`; large initial delay prevents auto-run during IT execution
- `retry-backoff-base-ms: 0` in test profile — eliminates `Thread.sleep()` in retry tests
- `ExpiredHoldRow` as a package-private nested record — accessible from same-package unit tests without needing public API surface

### Bugs / Surprises

None — all tests passed on first compile run for both stages.

### Next Session

**Start with Stage 10: booking-service Cancellation + History.**
- Read only the Stage 10 section of `CODING_PLAN.md` before starting
- `DELETE /api/v1/bookings/{bookingId}` + `GET /api/v1/bookings` (history)
- Need to DEL Redis hold key in cancellation flow (per ADR: stale keys block new holds for up to 30 min)

---

## Session 2026-03-03 — Stage 7: booking-service Hold Creation

**Phase:** 1 — Foundation
**Started at:** Stage 7 — booking-service: Hold Creation (first NOT STARTED stage)
**Ended at:** Stage 7 COMPLETE. Stage 8 is next.

### What Was Accomplished

**`POST /api/v1/holds` fully implemented** — all 8 steps of the spec operation sequence, all acceptance criteria met:

- `HoldController` validates `Idempotency-Key` header (400 if missing/invalid UUID)
- `HoldService.createHold()` implements steps 2–8 exactly per spec:
  - Step 2: Postgres idempotency check (`findBySessionIdAndStatus`)
  - Step 3: venue-service slot verification (`SlotVerificationClient`)
  - Step 4: holdId + expiresAt generation
  - Step 5: Redis SETNX all-or-nothing (rolls back successfully-SET keys on any failure)
  - Step 6: Postgres transaction via `TransactionTemplate` — INSERT holds + UPDATE slots WHERE status='AVAILABLE'; row count assertion as secondary gate
  - Step 7: Non-fatal `DEL slots:{venueId}:{date}` cache invalidation
  - Step 8: 200 response
- `RedisHoldRepository`: `setnx` (NX EX 1800), `del`, `deleteSlotCache`
- `SlotVerificationClient`: wraps `venueServiceClient` RestClient; 404 → `SlotNotFoundException`
- `GlobalExceptionHandler`: maps all domain exceptions to spec error codes
- Test stubs `V0__create_stub_tables.sql` updated to add `status` column to stub `slots` table

**Tests — all passing:**
- 4 unit tests (`HoldServiceTest`): idempotency, SETNX rollback, PG row-count mismatch, happy path
- 5 integration tests (`HoldControllerIT`): happy path + Redis TTL assertion, 400, 409 SETNX, 409 PG mismatch, idempotent replay, concurrency (10 threads → exactly 1 wins)

### Decisions Made This Session

- `TransactionTemplate` (not `@Transactional`) to scope Postgres phase independently of Redis SETNX
- `auth.setDetails(userId)` in `JwtAuthenticationFilter` to carry userId without a second JWT parse
- `JdbcTemplate.update(PreparedStatementCreator)` with `createArrayOf("uuid", ...)` for the slot UPDATE — gives exact row count for the row-count mismatch gate
- `@MockitoBean` (Spring Boot 3.5.x) replaces deprecated `@MockBean` going forward

### Bugs / Surprises

1. Multi-catch compiler error: `RedisConnectionFailureException | DataAccessException` — subclass relation forbidden. Fixed by catching only `DataAccessException`. (BUGS.md)
2. Mockito `InvalidUseOfMatchersException`: raw UUID + `any()` matcher — fixed with `eq(slotId)`. (BUGS.md)
3. `@MockBean` deprecated in Spring Boot 3.5.0 — replaced with `@MockitoBean`. (BUGS.md)
4. `containsExactly(String)` on `List<?>` fails to compile — fixed with `@SuppressWarnings("unchecked")` cast to `List<String>`.

### Next Session

**Start with Stage 8: booking-service Booking Confirmation.**
- Read only the Stage 8 section of `CODING_PLAN.md` before starting
- Critical: Postgres commits BEFORE Redis DEL (ADR-008) — enforce this order exactly
- The `HoldRepository.findBySessionIdAndStatus()` query is already in place
- `BookingRepository` (empty) is in place from Stage 6 — will need queries added
- The `RedisHoldRepository.getRawHold()` method is available for the Redis key verification step

---

## Session 2026-03-02 — Stage 6: booking-service Foundation + Service JWT

**Phase:** 1 — Foundation
**Started at:** Stage 6 — booking-service: Foundation + Service JWT (first NOT STARTED stage)
**Ended at:** Stage 6 COMPLETE. Stage 7 is next.

### What Was Accomplished

**booking-service — new foundation:**
- `build.gradle.kts` updated: added `spring-boot-starter-security`, `spring-boot-starter-validation`, `spring-boot-starter-data-redis`, `jjwt-api:0.12.6`, `spring-security-test`
- `AbstractIntegrationTest` rewritten to use static initializer pattern (was incorrectly using `@Testcontainers/@Container` which stops containers between test classes)
- Flyway migrations: `V1__create_holds.sql` (holds table, cross-service FKs to users + slots), `V2__create_bookings.sql` (bookings table)
- Test-only `V0__create_stub_tables.sql` in `src/test/resources/db/migration/` creates minimal `users` + `slots` stubs to satisfy FK constraints in the single Testcontainers Postgres container
- JPA entities: `Hold` + `Booking` (both `Persistable<UUID>`), `HoldStatus` + `BookingStatus` enums, `HoldRepository` + `BookingRepository`
- `ServiceJwtService`: generates short-lived JWTs (`sub: booking-service`, `iss: seatlock-internal`, 5-min TTL, HS256)
- Security stack: `JwtConfig` (separate `@Configuration` for `JwtUtils` bean), `JwtAuthenticationFilter`, `SecurityConfig` (health/error public, all else requires auth)
- `VenueServiceClientConfig`: `RestClient` bean using a `ClientHttpRequestInterceptor` to inject a fresh service JWT on every request (not static `defaultHeader`)
- `application.yml` updated with: `seatlock.jwt.secret`, `seatlock.service-jwt.*`, `seatlock.venue-service.base-url`, Redis config, Jackson date config
- Unit tests: `ServiceJwtServiceTest` (4 tests — subject, issuer, expiry window, signature) — all pass

**venue-service — service JWT validation layer:**
- `ServiceJwtAuthenticationFilter`: `@Component`; only activates on `/api/v1/internal/**` (via `shouldNotFilter`); validates service JWT against `seatlock.service-jwt.secret`; checks `sub == "booking-service"`; 401 on any failure
- `JwtAuthenticationFilter` updated: `shouldNotFilter` now returns `true` for `/api/v1/internal/**` — the two filters are non-overlapping
- `SecurityConfig` updated: added `/api/v1/internal/**` requires `ROLE_SERVICE` (before the public GET catch-all); `ServiceJwtAuthenticationFilter` added to chain
- `InternalSlotController`: `GET /api/v1/internal/slots?ids=...` — returns `[{slotId, venueId, startTime, status}]`; 404 if any ID not found
- `InternalSlotResponse` record DTO
- `GlobalExceptionHandler` updated: added `SlotNotFoundException` handler → 404 SLOT_NOT_FOUND
- `application.yml` + `application-test.yml` updated with `seatlock.service-jwt.secret`
- Integration tests: `InternalSlotControllerIT` (5 tests — valid JWT 200, no JWT 401, user JWT 401, expired JWT 401, unknown slot 404) — all pass

**Test results:** All 9 new tests pass; all pre-existing tests still pass (no regressions).

### Decisions Made This Session

| Decision | Chosen | Rejected | Reason |
|----------|--------|----------|--------|
| Service JWT filter implementation | `ServiceJwtAuthenticationFilter` creates its own `JwtUtils` instance from `@Value` secret | Separate `@Configuration` bean producing a second named `JwtUtils` | Avoids bean ambiguity — two `JwtUtils` beans of the same type would break `JwtAuthenticationFilter` autowiring |
| Filter path separation | `shouldNotFilter` on both filters to create non-overlapping paths | Single filter checking path and delegating | Cleaner — each filter has exactly one responsibility; no conditional branching inside `doFilterInternal` |
| RestClient token injection | `requestInterceptor` generating a fresh token per request | `defaultHeader` with static value | `defaultHeader` evaluates once at bean-creation; interceptor respects the 5-minute JWT TTL |
| Cross-service FK test strategy | `V0__create_stub_tables.sql` in `src/test/resources/db/migration/` | Separate test Flyway location, in-memory H2, removing FKs in test profile | Stub tables picked up automatically (test resources on integrationTest classpath); zero config overhead; same DDL in test and prod |

### Gotchas / Surprises

- **First IT run showed a spurious Docker `IllegalStateException`** — this was a Gradle test-result cache artifact from a previous failing run. Forcing `--rerun-tasks` ran cleanly on first attempt. Not a real bug.
- **Cross-service FKs require stub tables** — V1's `REFERENCES users(user_id)` and `REFERENCES slots(slot_id)` will fail Flyway in a fresh Testcontainers Postgres unless those tables exist first. The `V0__create_stub_tables.sql` in test resources is the solution; it is invisible to production (test resources are not packaged).

### What the Next Session Should Do First

1. Read CONTEXT.md + INDEX.md + CODING_PLAN.md + open-questions.md (standard session start)
2. The first NOT STARTED stage is **Stage 7 — booking-service: Hold Creation** (`POST /api/v1/holds`)
3. Stage 7 has a mandatory exact operation sequence in CODING_PLAN.md — read it carefully before writing any code
4. The Service JWT handshake (Stage 6) is the prerequisite — it is complete and tested

---

## Session 2026-03-01 — Stage 5: venue-service Availability Cache

**Phase:** 1 — Foundation
**Started at:** Stage 5 — venue-service: Availability Cache (first NOT STARTED stage)
**Ended at:** Stage 5 COMPLETE. Stage 6 is next.

### What Was Accomplished

- Added Redis caching to `GET /venues/{venueId}/slots`:
  - `spring-boot-starter-data-redis` dependency added to `venue-service/build.gradle.kts`
  - Redis config added to `application.yml` (`host: localhost`, `port: 6379`, `slots-ttl-seconds: 5`)
  - Test profile gets `slots-ttl-seconds: 1` to allow TTL expiry to be verified in tests without a long sleep
  - `AbstractIntegrationTest` updated: `GenericContainer("redis:7")` added to static initializer alongside Postgres; `spring.data.redis.host/port` wired via `@DynamicPropertySource`
  - `SlotCacheService` created: `buildKey(venueId, date)` → `"slots:{venueId}:{date}"`, `get(key)` → `Optional<List<SlotResponse>>`, `put(key, slots)` with configured TTL. Uses `StringRedisTemplate` + `ObjectMapper`. Serialization/deserialization errors are swallowed (logged as WARN) so cache failures never break the read path.
  - `VenueService.getSlots()` updated: cache-first path for date-scoped queries (HIT → filter + return; MISS → Postgres → cache → filter → return). Undated queries bypass cache. `applyStatusFilter()` extracted as a private helper used by both code paths.
- Unit tests: `SlotCacheServiceTest` (5 tests) — key format, cache miss, round-trip field preservation including `Instant` serialization, TTL setting, corrupt JSON graceful handling — all pass
- Integration tests: `SlotCacheIT` (5 tests) — Redis key populated on first request, key present + data consistent on second request, TTL expiry confirmed at 1.5s sleep, status filter applied from cached data, endTime derived from cached data — all pass
- Existing `VenueControllerIT` (11 tests) still pass — no regressions

### Decisions Made This Session

| Decision | Chosen | Rejected | Reason |
|----------|--------|----------|--------|
| Cache service structure | Separate `SlotCacheService` class | Inline Redis logic in `VenueService` | Isolates cache concerns; makes key construction and serialization independently unit-testable |
| Redis template | `StringRedisTemplate` + manual `ObjectMapper` | Spring `@Cacheable` / `CacheManager` | `@Cacheable` hides the key, making it impossible for booking-service to issue targeted `DEL` on keys it doesn't own |
| Cache scope | Date-scoped queries only | All slot queries | Undated query has no partition key; the hot path (5s frontend poll) always provides a date |
| IT verification of cache hit | Redis key presence + response equality | `@MockitoSpyBean` call count | ITs should test observable outcomes, not internal call paths. Spy also forces a new Spring context (cost); key-based assertion is stronger and simpler |

### Gotchas / Surprises

- **`UnnecessaryStubbingException` when `@BeforeEach` Redis stub is unused by a test that never touches Redis.** `buildKey_formatsCorrectly()` only calls the pure string method — no Redis calls at all — so the `when(redis.opsForValue())` setup stub is flagged as unnecessary. Fixed with `Mockito.lenient().when(...)` for that specific setup line. See BUGS.md.
- **`@MockitoSpyBean` deprecation warning in Spring Boot 3.5.** `org.springframework.boot.test.mock.mockito.SpyBean` is deprecated; the replacement is `org.springframework.test.context.bean.override.mockito.MockitoSpyBean`. Updated import before removing the spy entirely.
- **`@MockitoSpyBean` forces a new Spring context.** Any IT class that declares a `@MockitoSpyBean` gets its own application context — Spring cannot reuse the shared context because the bean graph is different. Removing the spy from `SlotCacheIT` restored context sharing with `VenueControllerIT`.

### Where to Continue

**Next session starts at:** `docs/CODING_PLAN.md` → Stage 6 — booking-service: Foundation + Service JWT

**Feed these four files to the new session:**
1. `docs/CONTEXT.md`
2. `docs/INDEX.md`
3. `docs/CODING_PLAN.md`
4. `docs/open-questions.md`

---

## Session 2026-03-01 — Stage 4: venue-service Venue + Slot CRUD

**Phase:** 1 — Foundation
**Started at:** Stage 4 — venue-service: Venue + Slot CRUD (first NOT STARTED stage)
**Ended at:** Stage 4 COMPLETE. Stage 5 is next.

### What Was Accomplished

- Implemented venue-service's full CRUD layer (Postgres only, no Redis yet):
  - Flyway migrations: `V1__create_venues.sql`, `V2__create_slots.sql` with FK and indexes
  - `VenueStatus` / `SlotStatus` enums matching DB check constraints
  - `Venue` and `Slot` JPA entities using `Persistable<UUID>` pattern
  - `VenueRepository` (`findByStatus`), `SlotRepository` (date query, range query, ordered)
  - DTOs: `CreateVenueRequest`, `UpdateVenueStatusRequest`, `GenerateSlotsRequest`, `VenueResponse`, `SlotResponse`
  - `JwtConfig` → `JwtUtils` bean (separate from SecurityConfig to avoid circular dep)
  - `JwtAuthenticationFilter` using `JwtUtils` from common module
  - `SecurityConfig`: GETs fully public; `/api/v1/admin/**` requires ADMIN role; 401 entry point
  - `SlotGenerationService`: Mon–Fri 09:00–17:00 UTC, 60-min blocks, duplicate-safe, bulk insert
  - `VenueService`: CRUD + slot query with app-layer status filter + endTime derivation
  - `VenueController` (`GET /api/v1/venues`, `GET /api/v1/venues/{id}/slots`)
  - `AdminVenueController` (`POST /admin/venues`, `PATCH /{id}/status`, `POST /{id}/slots/generate`)
  - `GlobalExceptionHandler` (404 VENUE_NOT_FOUND, 400 VALIDATION_ERROR)
  - Fixed `AbstractIntegrationTest` — removed @Testcontainers/@Container, replaced with static initializer, removed Redis (Stage 4 is Postgres-only), made class `public`
- Unit tests: `SlotGenerationServiceTest` (4 tests) — all pass
- Integration tests: `VenueControllerIT` (11 tests) + `VenueServiceApplicationIT` (2 tests) — all pass

### Decisions Made This Session

| Decision | Chosen | Rejected | Reason |
|----------|--------|----------|--------|
| GET auth | Fully public (no Bearer token required) | Authenticated USER | "Browse before login" is natural product behaviour; keeps React simpler |
| JwtUtils bean location | Separate `JwtConfig` class | In `SecurityConfig` | `SecurityConfig` → `JwtAuthenticationFilter` → `JwtUtils` (in SecurityConfig) → `SecurityConfig`: circular dependency. Separate config breaks the cycle |
| Slot FK mapping | Raw `@Column(name = "venue_id") UUID` | `@ManyToOne Venue` | Service never navigates Slot → Venue at runtime; raw UUID avoids unnecessary lazy-load config |
| Status filter | Applied in service layer (Java stream) | SQL WHERE clause | Lets the full slot list be cached as a single Redis key in Stage 5; SQL filter would require separate cache entries per status |

### Gotchas / Surprises

- **JwtUtils bean in SecurityConfig causes circular dependency.** SecurityConfig depends on JwtAuthenticationFilter (constructor); JwtAuthenticationFilter depends on JwtUtils (constructor); JwtUtils is a @Bean defined inside SecurityConfig → cycle. Fixed by extracting `JwtConfig` as a separate `@Configuration` class.
- **Lenient Mockito stubs in `@BeforeEach` when a test overrides the same stub.** `skipsExistingSlots` re-stubs `findByVenueIdAndStartTimeBetween`, making the `@BeforeEach` stub unused for that test. Mockito strict mode throws `UnnecessaryStubbingException`. Fixed with `lenient().when(...)` on shared stubs.

### Where to Continue

**Next session starts at:** `docs/CODING_PLAN.md` → Stage 5 — venue-service: Availability Cache

**Feed these four files to the new session:**
1. `docs/CONTEXT.md`
2. `docs/INDEX.md`
3. `docs/CODING_PLAN.md`
4. `docs/open-questions.md`

---

## Session 2026-03-01 — Stage 3: user-service Auth

**Phase:** 1 — Foundation
**Started at:** Stage 3 — user-service: Auth (first NOT STARTED stage)
**Ended at:** Stage 3 COMPLETE. Stage 4 is next.

### What Was Accomplished

- Implemented full authentication layer for user-service:
  - Flyway migration `V1__create_users.sql` — `users` table with UUID PK, email unique index
  - `User` JPA entity using `Persistable<UUID>` for client-side UUID generation
  - `UserRepository` (Spring Data JPA) — `existsByEmail`, `findByEmail`
  - DTOs: `RegisterRequest`, `RegisterResponse`, `LoginRequest`, `LoginResponse` (Java records)
  - `JwtService` — HMAC-SHA256, 24h TTL, issues userId + role claims
  - `JwtAuthenticationFilter` — extracts Bearer token, populates `SecurityContextHolder`
  - `SecurityConfig` — stateless, explicit 401 entry point, `/error` in permitAll
  - `UserRegistrationService` — email dedup, BCrypt hash, save
  - `AuthenticationService` — lookup, password verify, token issue
  - `AuthController` — `POST /api/v1/auth/register` (201), `POST /api/v1/auth/login` (200)
  - `GlobalExceptionHandler` — 409 for EMAIL_ALREADY_EXISTS, 401 for INVALID_CREDENTIALS, 400 for validation
- Unit tests: `UserRegistrationServiceTest`, `JwtServiceTest` — all pass
- Integration tests: `AuthControllerIT` (6 tests: register, duplicate email, login, wrong password, JWT auth, token claims) — all pass
- Fixed `JwtUtils.getUserId()` in common module to return `String` (UUID, not Long)
- Added JaCoCo to root Gradle build; updated GitHub Actions CI to upload test reports and coverage artifacts

### Decisions Made This Session

| Decision | Chosen | Rejected | Reason |
|----------|--------|----------|--------|
| UUID generation | Client-side (`UUID.randomUUID()` in field) + `Persistable<UUID>` | Database `gen_random_uuid()` | Unit tests need a non-null ID before persistence; DB default requires a round-trip |
| JWT token storage | 24h TTL, HMAC-SHA256 | Short-lived + refresh token | Sufficient for current scope; refresh token adds complexity deferred to Phase 2 |
| Password hashing | BCrypt (Spring default) | Argon2, scrypt | BCrypt is the Spring Security default; upgrade path clear if needed |

### Gotchas / Surprises

- **`Persistable<UUID>` is required when the ID is pre-initialized.** Without it, Hibernate sees a non-null `@Id` and issues `UPDATE` instead of `INSERT` → `ObjectOptimisticLockingFailureException`.
- **Spring Security 6.x defaults to `Http403ForbiddenEntryPoint`** for unauthenticated requests — explicit `authenticationEntryPoint` is required to get 401.
- **Tomcat forwards unhandled exceptions to `/error`** — if `/error` is not in `permitAll`, Spring Security intercepts it and returns 401, completely hiding the real error. Must add `/error` to the permit list.
- **`@Testcontainers` annotation stops containers between test classes.** When a second IT class runs, the container is gone but Spring's cached `ApplicationContext` still points to the old URL → `Connection refused`. Fixed with a static initializer block (container lives for the JVM lifetime).
- **`AbstractIntegrationTest` must be `public`** — subclasses in a different package (e.g., `controller` subpackage) cannot extend a package-private base class.

### Where to Continue

**Next session starts at:** `docs/CODING_PLAN.md` → Stage 4 — venue-service: Venue + Slot CRUD

**Feed these four files to the new session:**
1. `docs/CONTEXT.md`
2. `docs/INDEX.md`
3. `docs/CODING_PLAN.md`
4. `docs/open-questions.md`

---

## Session 2026-02-28 — Stage 2: Testing Infrastructure

**Phase:** 1 — Foundation
**Started at:** Stage 2 — Testing Infrastructure (first NOT STARTED stage)
**Ended at:** Stage 2 COMPLETE. Stage 3 is next.

### What Was Accomplished

- Verified all Stage 2 scaffolding from a previous session was in place: `integrationTest` source sets, `AbstractIntegrationTest` base classes, `application-test.yml` profiles, and CI workflow.
- Created the 3 missing sample IT files (venue-service, booking-service, notification-service).
- Fixed Testcontainers compatibility with Docker Desktop 4.60.1 (see Gotchas below).
- All acceptance criteria confirmed passing:
  - `./gradlew test` — unit tests pass (no Docker required)
  - `./gradlew integrationTest` — all 4 services pass (Testcontainers + PostgreSQL containers spin up)
  - CI workflow already configured for both tasks
- Created `docs/BUGS.md` as a persistent bug/fix log.
- Updated closing prompt in `readme.md` to include a step 5 for BUGS.md documentation.

### Decisions Made This Session

| Decision | Chosen | Rejected | Reason |
|----------|--------|----------|--------|
| Force docker-java API version | `jvmArgs("-Dapi.version=1.44")` in each Gradle integrationTest task | `DOCKER_API_VERSION` env var | Testcontainers 1.21.0 shaded docker-java reads API version from JVM system property `api.version`, not from the env var |
| Docker transport | TCP `localhost:2375` (existing Windows env var) | Named pipe `docker_engine` or `docker_cli` | TCP confirmed working via curl; named pipe strategies have the same API version constraint and `docker_engine` is hardcoded in Testcontainers |

### Gotchas / Surprises

- **Docker Desktop 4.60.1 enforces minimum API version 1.44.** Testcontainers 1.21.0 defaults to 1.32 via its shaded docker-java, causing HTTP 400 on every Docker call. The shaded library does NOT read `DOCKER_API_VERSION` from the environment — it reads the JVM system property `api.version`. Setting `DOCKER_API_VERSION` as a Windows env var or Gradle environment() has zero effect on the shaded library.
- **NpipeSocketClientProviderStrategy hardcodes `//./pipe/docker_engine`** — it cannot be redirected to `docker_cli` via testcontainers.properties. Forcing this strategy makes things worse.
- **Docker Desktop 4.60.1 labels hint at `npipe://\\.\pipe\docker_cli`** in the 400 response body — this is the CLI socket for the new Docker CLI integration, not a drop-in replacement for testcontainers' named pipe strategy.

### Where to Continue

**Next session starts at:** `docs/CODING_PLAN.md` → Stage 3 — user-service: Auth

**Feed these four files to the new session:**
1. `docs/CONTEXT.md`
2. `docs/INDEX.md`
3. `docs/CODING_PLAN.md`
4. `docs/open-questions.md`

---

## Session 2026-02-27 — Phase 0 Complete + Implementation Plan Written

**Phase:** 0 → 1 transition
**Started at:** Section 6 Deep Dives (not yet started)
**Ended at:** Phase 0 fully complete; ready to begin Stage 1 of implementation

### What Was Accomplished

**Deep Dives (Section 6):**
- Answered four deep dive questions through interview format
- OQ-19: Crash recovery mid-confirmation — resolved (Postgres-before-Redis-DEL + DEL in cancellation)
- OQ-20: Cache invalidation strategy — resolved (DEL + 5s TTL sufficient)
- OQ-21: Redis unavailable fallback — resolved (503 degraded mode, user chose Option B)
- OQ-22: Vault unreachable on startup — resolved (fail fast; in-memory hold at runtime)
- Pre-deep-dive Q&A: Vault contents, Service JWT mechanics, Redis hold mechanism clarified

**Pre-session questions answered:**
- What does Vault store? → Full secret inventory now documented in CONTEXT.md
- Is Service JWT correct? → Yes; symmetric HMAC (shared secret); asymmetric RS256 is Phase 2+
- How does Redis hold work? → SETNX is the atomic concurrency gate; no Postgres contention
- What happens when Redis crashes? → 503 on POST /holds; GET /slots falls back to Postgres

**Phase 0 documents written this session:**
- `docs/system-design/06-deep-dives.md` — all four deep dives with rationale and alternatives
- `docs/diagrams/01-system-context.md` — external actors and dependencies
- `docs/diagrams/03-booking-flow-sequence.md` — hold creation, booking confirmation, hold expiry
- `docs/diagrams/04-data-model-erd.md` — full ERD with all 5 tables
- `docs/diagrams/05-infrastructure.md` — AWS topology diagram
- `docs/decisions/ADR-001.md` through `ADR-008.md` — all major architectural decisions
- `docs/M0-phase0-complete.md` — Phase 0 milestone sign-off

**Implementation plan written:**
- `docs/CODING_PLAN.md` — 16-stage plan; approved by user after stage breakdown review
- `docs/INDEX.md` — master navigation index for entire project

**Tracking files updated:**
- `docs/CONTEXT.md` — Section 6 decisions, Vault inventory, Phase 0 compromises, new session workflow
- `docs/open-questions.md` — OQ-19 through OQ-22 marked resolved
- `docs/PROJECT_PLAN.md` — Phase 0 marked complete; Phase 1 is next

### New Decisions Made This Session

| Decision | Chosen | Rejected | Reason |
|----------|--------|----------|--------|
| Booking confirmation operation order | Postgres commits before Redis DEL | Redis DEL first | Crash after DEL but before Postgres commit leaves unrecoverable orphaned state |
| Stale hold key cleanup | DEL hold:{slotId} in cancellation flow | Rely on TTL only | Stale key blocks new holds for up to 30 min after cancellation on a freed slot |
| Cache invalidation strategy | DEL + 5s TTL | Write-through, pub/sub | Sufficient at our scale and staleness tolerance; write-through adds coupling |
| Redis unavailable behaviour | 503 degraded mode (no booking) | Postgres SELECT FOR UPDATE fallback | Simpler; consistent with designed degraded mode; avoids two write code paths |
| Vault unreachable at startup | Fail fast (container exits) | Start with cached secrets | Cached secrets defeat rotation; ECS restarts automatically |
| Vault unreachable at runtime | Hold in-memory secrets; health degraded | Crash the service | A running service must not die over a transient Vault blip |

### Stage Breakdown Decisions (CODING_PLAN.md)

- 16 stages total (approved by user)
- Stage 2 added (Testing Infrastructure) after user asked when testing infra is set up
- Stage 16 (AWS/Terraform) kept at the end — local-first, cloud deployment last
- Stage 11 includes ElasticMQ in Docker Compose for local SQS simulation
- SQS publishing wired per stage (confirm, expiry, cancel) so notification-service in Stage 11 just builds the consumer
- Frontend split at Stage 14/15 — auth+browse / booking flows

### Where to Continue

**Next session starts at:** `docs/CODING_PLAN.md` → Stage 1 — Project Scaffolding + Docker Compose

**Feed these four files to the new session:**
1. `docs/CONTEXT.md`
2. `docs/INDEX.md`
3. `docs/CODING_PLAN.md`
4. `docs/open-questions.md`

**Use this prompt:**
```
I am sharing four files with you. Read all four before doing anything else:
- CONTEXT.md       — all architecture decisions, key numbers, confirmed services
- INDEX.md         — navigation map for the entire project
- CODING_PLAN.md   — 16-stage implementation plan with exact operation sequences
- open-questions.md — all design questions (resolved, reference only)

We are building SeatLock — a distributed reservation platform with real-time
availability and concurrency-safe booking.

Phase 0 (system design) is complete. You are now implementing the project.

Once you have read all four files, tell me:
1. Which stage is currently IN PROGRESS or the first NOT STARTED stage
2. What that stage builds and what its acceptance criteria are
3. Any questions before we begin

Do not start writing any code yet. Wait for me to say "let's continue."
```

---
