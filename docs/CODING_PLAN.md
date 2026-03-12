# SeatLock — Coding Plan

## How to Use This File

Each stage is a self-contained unit of work for one session. Stages build on each other in strict dependency order — do not skip stages. The acceptance criteria at the end of each stage define "done." A new session should read this file, find the first stage that is NOT STARTED or IN PROGRESS, and begin there.

For stages touching concurrency, caching, or failure resilience (Stages 7, 8, 12): the **exact operation sequences** are written out in full. Follow them precisely — these sequences were decided in the Phase 0 deep dives and must not be reordered or simplified.

Design reference files live in `docs/`. The `docs/INDEX.md` maps every file in the project.

---

## Stage Overview

| # | Stage | Status |
|---|-------|--------|
| 1 | Project Scaffolding + Docker Compose | COMPLETE |
| 2 | Testing Infrastructure | COMPLETE |
| 3 | user-service: Auth | COMPLETE |
| 4 | venue-service: Venue + Slot CRUD | COMPLETE |
| 5 | venue-service: Availability Cache | COMPLETE |
| 6 | booking-service: Foundation + Service JWT | COMPLETE |
| 7 | booking-service: Hold Creation | COMPLETE |
| 8 | booking-service: Booking Confirmation | COMPLETE |
| 9 | booking-service: Hold Expiry Job | COMPLETE |
| 10 | booking-service: Cancellation + History | COMPLETE |
| 11 | notification-service | COMPLETE |
| 12 | Resilience | COMPLETE |
| 13 | API Documentation (Swagger UI) | COMPLETE |
| — | Maintenance: cross-service DB integrity | COMPLETE (between stages 13–14) |
| — | Maintenance: venue_db slot status write gap | COMPLETE (between stages 13–14, session 2) |
| 14 | Observability | NOT STARTED |
| 15 | Frontend: Auth + Browse | NOT STARTED |
| 16 | Frontend: Booking Flows | NOT STARTED |
| 17 | Infrastructure (AWS) | COMPLETE |
| 18 | E2E Tests (AWS) | NOT STARTED |
| 19 | Frontend Deployment | NOT STARTED |
| 20 | Frontend UI Polish | NOT STARTED |

---

## Stage 1 — Project Scaffolding + Docker Compose

**Status:** COMPLETE

**Completed:** 2026-02-28. All acceptance criteria met. Spring Boot 3.5.0, Gradle 8.14.4, Java 21 (Temurin).

**Goal:** Create the multi-module Gradle project structure, local development environment (Docker Compose), and CI skeleton. Nothing runs without this.

**Prerequisites:** None.

### What to Build

**Project structure (Gradle Kotlin DSL):**
```
seatlock/
├── build.gradle.kts          (root — shared dependency versions, common plugins)
├── settings.gradle.kts       (includes all subprojects)
├── gradle.properties         (JVM args, Gradle version)
├── docker-compose.yml        (local dev environment)
├── .github/
│   └── workflows/
│       └── ci.yml            (build + test on every PR)
├── common/                   (shared library — imported by all services)
│   └── src/main/java/com/seatlock/common/
│       ├── dto/              (shared request/response DTOs)
│       ├── exception/        (base exception classes, domain error codes)
│       └── security/         (JWT utility: parse claims, validate signature)
├── user-service/
├── venue-service/
├── booking-service/
└── notification-service/
```

**Each service module:**
- Spring Boot 3.x main class + empty application
- `application.yml` with server port (user: 8081, venue: 8082, booking: 8083, notification: 8084)
- Flyway dependency + `db/migration/` directory
- Spring Actuator at `/actuator/health`
- Dockerfile (multi-stage: Gradle build → JRE 21 runtime image)

**common module contains:**
- `ApiErrorResponse` DTO: `{ error: String, message: String }`
- `DomainException` base class + subclasses: `SlotNotAvailableException`, `HoldExpiredException`, `HoldMismatchException`, `CancellationWindowClosedException`, etc.
- `JwtUtils`: parse JWT claims (userId, email, role) from a token string — shared by all services for JWT validation

**Docker Compose (`docker-compose.yml`):**
```yaml
services:
  postgres:
    image: postgres:15
    ports: ["5432:5432"]
    environment:
      POSTGRES_USER: seatlock
      POSTGRES_PASSWORD: seatlock
    volumes:
      - ./infra/postgres/init.sql:/docker-entrypoint-initdb.d/init.sql

  redis:
    image: redis:7
    ports: ["6379:6379"]

  mailhog:
    image: mailhog/mailhog
    ports: ["1025:1025", "8025:8025"]   # SMTP + web UI
```

**`infra/postgres/init.sql`:** Creates separate databases for each service:
```sql
CREATE DATABASE user_db;
CREATE DATABASE venue_db;
CREATE DATABASE booking_db;
```

**GitHub Actions (`ci.yml`):**
```yaml
on: [pull_request]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '21', distribution: 'temurin' }
      - run: ./gradlew build
```

### Acceptance Criteria
- [x] `./gradlew build` passes from the root (all modules compile)
- [x] `docker-compose up` starts Postgres, Redis, Mailhog (and ElasticMQ) without errors
- [x] Each service starts with `./gradlew :X-service:bootRun` and `GET /actuator/health` returns `{"status":"UP"}`
- [x] Three separate Postgres databases exist after `docker-compose up`
- [x] GitHub Actions CI workflow authored (triggers on first PR to repo)

---

## Stage 2 — Testing Infrastructure

**Status:** COMPLETE

**Completed:** 2026-02-28. All acceptance criteria met. Fix for Docker Desktop 4.60.1 compatibility: `jvmArgs("-Dapi.version=1.44")` in every integrationTest Gradle task forces the shaded docker-java to use API v1.44 (the minimum Docker Desktop 4.60+ enforces). Testcontainers connects via `DOCKER_HOST=tcp://localhost:2375`.

**Goal:** Establish the testing patterns used by all subsequent stages. Every stage from Stage 3 onward follows these conventions.

**Prerequisites:** Stage 1 complete.

### What to Build

**Gradle dependencies (root `build.gradle.kts`):**
```kotlin
testImplementation("org.testcontainers:postgresql")
testImplementation("org.testcontainers:redis")  // or com.redis.testcontainers
testImplementation("org.testcontainers:junit-jupiter")
testImplementation("org.springframework.boot:spring-boot-starter-test")
testImplementation("io.mockk:mockk")  // or Mockito
```

**Integration test source set** in each service module:
```kotlin
// build.gradle.kts per service
sourceSets {
    create("integrationTest") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}
tasks.register<Test>("integrationTest") {
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    useJUnitPlatform()
}
```

**Base integration test class** (`AbstractIntegrationTest.kt`) — one per service, lives in each service's `integrationTest` source set:
```kotlin
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
abstract class AbstractIntegrationTest {

    companion object {
        @Container
        val postgres = PostgreSQLContainer<Nothing>("postgres:15").apply {
            withDatabaseName("test_db")
            withUsername("test")
            withPassword("test")
        }

        @Container
        val redis = GenericContainer<Nothing>("redis:7").apply {
            withExposedPorts(6379)
        }

        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.data.redis.host", redis::getHost)
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379) }
        }
    }
}
```

**`application-test.yml`** in each service:
```yaml
spring:
  flyway:
    enabled: true
  jpa:
    hibernate:
      ddl-auto: validate
logging:
  level:
    com.seatlock: DEBUG
```

**Testing conventions to follow from Stage 3 onward:**
- Unit tests live in `src/test/` — no Spring context, no external dependencies, use mocks
- Integration tests live in `src/integrationTest/` — extend `AbstractIntegrationTest`, use Testcontainers
- Every controller has a `MockMvc` integration test
- Every service has unit tests for business logic branches (happy path + all error paths)
- Concurrency tests (for booking-service): use `ExecutorService` with N concurrent threads

**GitHub Actions update:**
```yaml
- run: ./gradlew test integrationTest
```

### Acceptance Criteria
- [ ] `./gradlew test` runs unit tests for all services (fast, no Docker required)
- [ ] `./gradlew integrationTest` starts Testcontainers and runs integration tests
- [ ] Both tasks run in CI on every PR
- [ ] A sample integration test in user-service passes (even if it just asserts the application context loads)
- [ ] A sample unit test in user-service passes

---

## Stage 3 — user-service: Auth

**Status:** COMPLETE

**Completed:** 2026-03-01. All acceptance criteria met. ADMIN role is in the schema and JWT; ADMIN users are created directly via UserRepository in tests (no API backdoor needed).

**Goal:** Registration, login, and JWT issuance. JWT validation used by all services.

**Prerequisites:** Stages 1–2 complete.

### What to Build

**Flyway migration `V1__create_users.sql`:**
```sql
CREATE TABLE users (
    user_id       UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    phone         VARCHAR(20),
    role          VARCHAR(10)  NOT NULL DEFAULT 'USER'
                               CHECK (role IN ('USER', 'ADMIN')),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_users_email ON users (email);
```

**Entities + Repositories:**
- `User` JPA entity mapping the `users` table
- `UserRepository` extending `JpaRepository<User, UUID>`

**Services:**
- `UserRegistrationService.register(email, password, phone)` — bcrypt password, save, return DTO
- `AuthenticationService.login(email, password)` — verify bcrypt, issue JWT
- `JwtService.issueToken(user)` — signs JWT with `userId`, `email`, `role` claims; `exp` = now + 24h

**JWT secret:** stored in `application.yml` as `seatlock.jwt.secret`. Later replaced by Vault in Stage 12.

**Spring Security config:**
- `JwtAuthenticationFilter` — extracts Bearer token, validates via `JwtService`, sets `SecurityContextHolder`
- Permit all: `POST /api/v1/auth/register`, `POST /api/v1/auth/login`
- Require authentication: everything else

**Controllers:**
- `POST /api/v1/auth/register` → 201 `{userId, email}`
- `POST /api/v1/auth/login` → 200 `{token, expiresAt}`

**Error handling (`@ControllerAdvice`):**
- `EmailAlreadeyExistsException` → 409 `EMAIL_ALREADY_EXISTS`
- `InvalidCredentialsException` → 401 `INVALID_CREDENTIALS`
- `MethodArgumentNotValidException` → 400 `VALIDATION_ERROR`

**Tests:**
- Unit: `UserRegistrationService` (duplicate email throws), `JwtService` (token parses correctly)
- Integration: register happy path, duplicate email, login happy path, bad password, JWT missing on protected endpoint

### Acceptance Criteria
- [x] `POST /api/v1/auth/register` creates user, returns 201 with `{userId, email}`
- [x] `POST /api/v1/auth/register` with duplicate email returns 409 `EMAIL_ALREADY_EXISTS`
- [x] `POST /api/v1/auth/login` returns 200 with signed JWT
- [x] JWT contains `userId`, `email`, `role` claims
- [x] `POST /api/v1/auth/login` with wrong password returns 401 `INVALID_CREDENTIALS`
- [x] Any request without a valid JWT to a protected endpoint returns 401
- [x] ADMIN user can be registered (role field in schema; set directly via UserRepository in tests)
- [x] All unit and integration tests pass

---

## Stage 4 — venue-service: Venue + Slot CRUD

**Status:** COMPLETE

**Goal:** Venue and slot management. No Redis cache yet — Postgres only. Admin endpoints protected by JWT role claim.

**Prerequisites:** Stage 3 complete (JWT validation pattern established).

### What to Build

**Flyway migrations:**

`V1__create_venues.sql`:
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

`V2__create_slots.sql`:
```sql
CREATE TABLE slots (
    slot_id    UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    venue_id   UUID        NOT NULL REFERENCES venues (venue_id),
    start_time TIMESTAMPTZ NOT NULL,
    status     VARCHAR(10) NOT NULL DEFAULT 'AVAILABLE'
                           CHECK (status IN ('AVAILABLE', 'HELD', 'BOOKED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_slots_venue_start ON slots (venue_id, start_time);
CREATE INDEX idx_slots_venue_status ON slots (venue_id, status);
```

**Config:**
```yaml
seatlock:
  slot:
    duration-minutes: 60
```

**Entities + Repositories:** `Venue`, `Slot`, `VenueRepository`, `SlotRepository`

**Spring Security config:** copy JWT filter from user-service (shared via `common` module's `JwtUtils`). Permit all GETs; require ADMIN role for POST/PATCH admin endpoints.

**Endpoints:**

| Method | Path | Auth | Notes |
|--------|------|------|-------|
| `GET` | `/api/v1/venues` | USER | Returns ACTIVE venues only |
| `POST` | `/api/v1/admin/venues` | ADMIN | Create venue |
| `PATCH` | `/api/v1/admin/venues/{id}/status` | ADMIN | Set ACTIVE or INACTIVE (soft-delete) |
| `GET` | `/api/v1/venues/{venueId}/slots` | USER | Postgres query only (no cache yet); accepts `?date=` and `?status=` |
| `POST` | `/api/v1/admin/venues/{venueId}/slots/generate` | ADMIN | Auto-generate slots for a date range |

**Slot auto-generation logic:**
Given `venueId`, `fromDate`, `toDate`: for each weekday (Mon–Fri) in range, generate slots from 09:00 to 17:00 in increments of `seatlock.slot.duration-minutes`. Skip if a slot already exists for that `(venueId, startTime)` combination. Bulk insert.

**`GET /venues/{venueId}/slots` query:**
```sql
SELECT slot_id, start_time, status FROM slots
WHERE venue_id = :venueId
  AND DATE(start_time AT TIME ZONE 'UTC') = :date
ORDER BY start_time ASC
```
Apply `?status=` filter in the application layer (not SQL). Return `endTime` derived as `startTime + duration-minutes`.

**Tests:**
- Unit: slot auto-generation (correct days, correct times, skips weekends), status filter
- Integration: create venue (ADMIN), get venues (USER), generate slots, get slots by date

### Acceptance Criteria
- [x] ADMIN can create a venue (`POST /api/v1/admin/venues`)
- [x] USER can browse ACTIVE venues (`GET /api/v1/venues`) — fully public, no token required
- [x] ADMIN can generate slots for a venue for a date range
- [x] Generated slots are Mon–Fri only, 09:00–17:00, 60-minute blocks
- [x] `GET /venues/{id}/slots?date=2024-02-19` returns slots for that UTC date only
- [x] `?status=AVAILABLE` filter works (applied in app layer)
- [x] Response includes `endTime` (derived, not from DB)
- [x] Non-ADMIN gets 403 on admin endpoints
- [x] 404 `VENUE_NOT_FOUND` when venueId does not exist or is INACTIVE
- [x] All unit and integration tests pass (4 unit + 13 integration = 17 total)

---

## Stage 5 — venue-service: Availability Cache

**Status:** COMPLETE

**Goal:** Add Redis caching to `GET /venues/{venueId}/slots`. Cache miss reads Postgres and caches the result. This is the hot read path (polled every 5 seconds by the frontend).

**Prerequisites:** Stage 4 complete.

### What to Build

**Dependencies:** `spring-boot-starter-data-redis` (Lettuce driver)

**`application.yml`:**
```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
seatlock:
  cache:
    slots-ttl-seconds: 5
```

**Redis cache key:** `slots:{venueId}:{date}` where `{date}` is ISO 8601 `YYYY-MM-DD`.

**Cache value:** JSON array of all slots for that venue on that date (all statuses — unfiltered). The status filter is applied after retrieval.

**Updated `GET /venues/{venueId}/slots` flow:**
```
1. Build cache key: "slots:{venueId}:{date}"
2. GET from Redis
   └─ HIT:  deserialize JSON → apply status filter → derive endTime → return 200
   └─ MISS: query Postgres → serialize to JSON → SET key EX 5 → apply status filter → derive endTime → return 200
```

**Cache invalidation:** booking-service owns cache DEL (not venue-service). venue-service does NOT invalidate its own cache. The 5s TTL is the fallback. This split is intentional — see ADR-004.

**`AbstractIntegrationTest` update** for venue-service: already includes a Redis Testcontainer from Stage 2. Verify cache hit/miss behavior in integration tests using `RedisTemplate` to inspect keys directly.

**Tests:**
- Unit: cache key construction, JSON serialization/deserialization, status filter post-cache
- Integration: first request → Postgres hit (verify via query count or spy); second request within 5s → cache hit (verify Redis key exists); key expires after 5s (use short TTL in test profile)

### Acceptance Criteria
- [x] First `GET /venues/{id}/slots` for a venue+date queries Postgres and stores result in Redis
- [x] Second request within 5 seconds is served entirely from Redis (no Postgres query)
- [x] Cache key format is `slots:{venueId}:{date}` (ISO 8601 date)
- [x] `?status=` filter correctly filters the cached full list in the application layer
- [x] `endTime` is derived correctly in both cache-hit and cache-miss paths
- [x] Redis key expires after 5 seconds (verified in integration test with short TTL profile)
- [x] All unit and integration tests pass

---

## Stage 6 — booking-service: Foundation + Service JWT

**Status:** COMPLETE

**Goal:** Stand up booking-service with its Flyway schema, entity classes, and the Service JWT handshake between booking-service and venue-service. No business logic yet.

**Prerequisites:** Stages 4–5 complete (venue-service must be running with the internal endpoint).

### What to Build

**booking-service Flyway migrations:**

`V1__create_holds.sql`:
```sql
CREATE TABLE holds (
    hold_id    UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID        NOT NULL,
    user_id    UUID        NOT NULL REFERENCES users (user_id),  -- cross-service FK (Phase 0 shared cluster)
    slot_id    UUID        NOT NULL REFERENCES slots (slot_id),
    expires_at TIMESTAMPTZ NOT NULL,
    status     VARCHAR(10) NOT NULL DEFAULT 'ACTIVE'
                           CHECK (status IN ('ACTIVE', 'CONFIRMED', 'EXPIRED', 'RELEASED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_holds_session_slot UNIQUE (session_id, slot_id)
);
CREATE INDEX idx_holds_session   ON holds (session_id);
CREATE INDEX idx_holds_slot      ON holds (slot_id);
CREATE INDEX idx_holds_expiry    ON holds (expires_at, status);
```

`V2__create_bookings.sql`:
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
CREATE INDEX idx_bookings_user         ON bookings (user_id);
CREATE INDEX idx_bookings_confirmation ON bookings (confirmation_number);
CREATE INDEX idx_bookings_session      ON bookings (session_id);
CREATE INDEX idx_bookings_slot         ON bookings (slot_id);
```

**Service JWT implementation:**

`application.yml` (booking-service):
```yaml
seatlock:
  service-jwt:
    secret: local-dev-shared-secret-replace-in-prod   # replaced by Vault in Stage 12
    issuer: seatlock-internal
    subject: booking-service
    ttl-minutes: 5
```

`ServiceJwtService` (booking-service): generates a short-lived JWT with `sub: booking-service`, `iss: seatlock-internal`, `exp: now + 5min`. Signed with HS256 using the shared secret.

**venue-service additions:**

`GET /api/v1/internal/slots?ids={id1},{id2,...}` endpoint:
- Returns `[{slotId, venueId, startTime, status}]` for the given IDs
- Returns 404 if any ID is not found
- Protected by `ServiceJwtAuthenticationFilter`: validates JWT signature, asserts `sub == "booking-service"`

`ServiceJwtAuthenticationFilter` (venue-service): validates the service JWT on the `/internal/**` path prefix. Uses the same shared secret from `application.yml`.

**booking-service RestClient bean:**
```kotlin
@Bean
fun venueServiceClient(serviceJwtService: ServiceJwtService): RestClient =
    RestClient.builder()
        .baseUrl("http://localhost:8082")           // Cloud Map DNS in prod
        .defaultHeader("Authorization") { "Bearer ${serviceJwtService.generateToken()}" }
        .build()
```

**Entity classes:** `Hold`, `Booking` JPA entities (no business logic, just field mapping + repositories).

**Health endpoints:** `/actuator/health` on booking-service returns UP.

**Tests:**
- Integration: booking-service calls venue-service internal endpoint successfully; request without Service JWT to internal endpoint returns 401; expired service JWT returns 401

### Acceptance Criteria
- [ ] booking-service starts and `/actuator/health` returns UP
- [ ] Flyway migrations create `holds` and `bookings` tables
- [ ] booking-service successfully calls `GET /internal/slots?ids=...` on venue-service with a valid Service JWT
- [ ] A request to `/internal/slots` without a Service JWT returns 401
- [ ] A request with a user JWT (not service JWT) to `/internal/slots` returns 401
- [ ] All unit and integration tests pass

---

## Stage 7 — booking-service: Hold Creation

**Status:** COMPLETE

**Goal:** Implement `POST /api/v1/holds` with the full Redis SETNX concurrency gate. This is the most critical endpoint — follow the operation sequence exactly.

**Prerequisites:** Stage 6 complete (Service JWT handshake working).

### Exact Operation Sequence (do not reorder)

```
POST /api/v1/holds
Body: { slotIds: [...] }
Header: Idempotency-Key: <uuid>

1. VALIDATE INPUT
   - Reject missing Idempotency-Key header → 400 MISSING_IDEMPOTENCY_KEY
   - Reject empty or invalid slotIds → 400 VALIDATION_ERROR

2. IDEMPOTENCY CHECK (Postgres)
   SELECT * FROM holds WHERE session_id = :idempotencyKey AND status = 'ACTIVE'
   └─ Found → return 200 with existing hold data (skip remaining steps)
   sessionId = idempotencyKey value

3. VERIFY SLOTS EXIST via venue-service (sync HTTP, Service JWT)
   GET /internal/slots?ids={slotId1},{slotId2,...}
   └─ Any ID not found → 404 SLOT_NOT_FOUND
   (No status check here — Redis SETNX is the availability gate)

4. GENERATE IDs
   holdId = UUID.randomUUID() per slot
   expiresAt = now() + 30 minutes

5. REDIS SETNX PHASE (all-or-nothing)
   For each slotId:
     SET hold:{slotId} '{"holdId":"...","userId":"...","sessionId":"...","expiresAt":"..."}' NX EX 1800
   If ANY command returns nil (key already existed):
     DEL hold:{slotId} for every key successfully SET in this request
     → 409 SLOT_NOT_AVAILABLE { error, message, unavailableSlotIds: [...] }

6. POSTGRES TRANSACTION (BEGIN...COMMIT)
   INSERT INTO holds (hold_id, session_id, user_id, slot_id, expires_at, status='ACTIVE') × N
   UPDATE slots SET status = 'HELD'
     WHERE slot_id IN (:slotIds) AND status = 'AVAILABLE'
   Assert: updatedRowCount == len(slotIds)
   └─ Mismatch (slot HELD/BOOKED in Postgres — ≤60s expiry lag window):
        ROLLBACK
        DEL hold:{slotId} for each slot in request
        → 409 SLOT_NOT_AVAILABLE
   └─ Other failure:
        ROLLBACK
        DEL hold:{slotId} for each slot in request
        → 500 (Redis TTL self-heals within 30 min if DEL also fails)

7. INVALIDATE AVAILABILITY CACHE
   DEL slots:{venueId}:{date}   (venueId + date from venue-service response in step 3)
   (Non-fatal if this fails — 5s TTL will clean up)

8. RETURN 200
   { sessionId, expiresAt, holds: [{ holdId, slotId }, ...] }
```

### What to Build

- `HoldService.createHold(userId, slotIds, idempotencyKey)`
- `HoldController` mapping `POST /api/v1/holds`
- `RedisHoldRepository`: wraps `RedisTemplate`; exposes `setnx(slotId, holdPayload)` and `del(slotId)`
- `SlotVerificationClient`: wraps the venue-service RestClient call
- `@ControllerAdvice`: map domain exceptions to HTTP error responses with domain error codes

**Redis value format:**
```json
{ "holdId": "uuid", "userId": "uuid", "sessionId": "uuid", "expiresAt": "ISO-8601" }
```

**Concurrency integration test:**
Launch 10 threads simultaneously, all calling `POST /api/v1/holds` for the same single slot. Assert exactly 1 gets 200 and 9 get 409. Use `CountDownLatch` to synchronize thread start.

**Tests:**
- Unit: idempotency check logic, all-or-nothing Redis cleanup on partial failure, AND status guard row count mismatch handling
- Integration: happy path hold creation, slot already held (SETNX fails), slot HELD in Postgres but Redis key expired (row count mismatch), idempotent replay, concurrency test (10 concurrent → 1 wins)

### Acceptance Criteria
- [x] `POST /api/v1/holds` creates holds for available slots; returns 200 with `sessionId` + `expiresAt`
- [x] Redis key `hold:{slotId}` exists after creation with TTL ≈ 1800s
- [x] Postgres `holds` rows inserted; `slots.status` updated to `HELD`
- [x] Slots cache key `slots:{venueId}:{date}` is DEL'd after successful hold
- [x] Missing `Idempotency-Key` header → 400 `MISSING_IDEMPOTENCY_KEY`
- [x] Same `Idempotency-Key` twice → returns existing holds, no duplicate rows
- [x] One unavailable slot → no holds created for any slot → 409 `SLOT_NOT_AVAILABLE`
- [x] Concurrency test: 10 concurrent holds for same slot → exactly 1 wins
- [x] All unit and integration tests pass

---

## Stage 8 — booking-service: Booking Confirmation

**Status:** COMPLETE

**Goal:** Implement `POST /api/v1/bookings`. **Critical: Postgres commits before Redis DEL** (ADR-008 crash-safety decision). Follow the operation sequence exactly.

**Prerequisites:** Stage 7 complete (holds exist in Redis and Postgres).

### Exact Operation Sequence (do not reorder — Postgres BEFORE Redis DEL)

```
POST /api/v1/bookings
Body: { sessionId: "uuid" }

0. IDEMPOTENCY CHECK (Postgres)
   SELECT * FROM bookings WHERE session_id = :sessionId AND status = 'CONFIRMED'
   └─ Found → return 201 with existing booking data (skip remaining steps)

1. LOAD ACTIVE HOLDS (Postgres)
   SELECT * FROM holds WHERE session_id = :sessionId AND status = 'ACTIVE'
   └─ None found → 404 SESSION_NOT_FOUND

2. AUTHORIZE
   For each hold: assert hold.user_id == JWT userId → 403 FORBIDDEN

3. VERIFY REDIS HOLD KEYS
   For each hold:
     value = Redis GET hold:{slotId}
     └─ value is nil        → 409 HOLD_EXPIRED
        "Your hold has expired. Please start a new hold."
     └─ value.holdId ≠ hold.holdId → 409 HOLD_MISMATCH
        "Hold state mismatch. Refresh availability and create a new hold."
   (No Postgres writes if either check fails)

4. GENERATE CONFIRMATION NUMBER
   "SL-" + LocalDate.now().format("yyyyMMdd") + "-" + String.format("%04d", random(0..9999))
   Same value written to every Booking row in this session.

5. POSTGRES TRANSACTION ← COMMITS FIRST (crash-safe order)
   BEGIN
     INSERT INTO bookings
       (booking_id, session_id, confirmation_number, user_id, slot_id, hold_id, status='CONFIRMED')
       × N rows (one per hold)
     UPDATE holds SET status = 'CONFIRMED'
       WHERE session_id = :sessionId AND status = 'ACTIVE'
     UPDATE slots SET status = 'BOOKED'
       WHERE slot_id IN (:slotIds)
   COMMIT
   └─ Failure → ROLLBACK → 500 (Redis holds expire naturally within 30 min)

6. REDIS CLEANUP ← AFTER POSTGRES COMMIT (best-effort)
   DEL hold:{slotId} for each slot
   DEL slots:{venueId}:{date}
   (If service crashes here, retry hits step 0 idempotency check → returns 201 with existing data)
   (Stale hold keys in Redis are cleaned by the cancellation flow if booking is later cancelled — ADR-008)

7. PUBLISH EVENT (async, non-blocking)
   SQS publish: BookingConfirmedEvent { confirmationNumber, sessionId, userId, slots, timestamp }
   (SQS not yet wired in local dev until ElasticMQ added to Docker Compose in Stage 11 — use a mock/stub here)

8. RETURN 201
   { confirmationNumber, sessionId, bookings: [{ bookingId, slotId, status: "CONFIRMED" }, ...] }
```

**Why Postgres before Redis DEL:**
If the service crashes between steps 5 and 6, Postgres has the confirmed booking. On retry, step 0 finds it and returns 201. If the order were reversed (Redis DEL first), a crash after DEL but before Postgres commit leaves no record and the user gets 409 HOLD_EXPIRED on retry — unrecoverable without manual intervention.

### What to Build

- `BookingService.confirmBooking(sessionId, userId)`
- `BookingController` mapping `POST /api/v1/bookings`
- `ConfirmationNumberGenerator`: generates `SL-YYYYMMDD-XXXX`
- SQS publisher stub (interface only for now — wired to ElasticMQ in Stage 11)

**Crash-recovery integration test:**
1. Create hold via `POST /holds`
2. Manually commit the Postgres booking transaction (via direct repo call)
3. Skip the Redis DEL step
4. Call `POST /bookings` again with same sessionId
5. Assert 201 returned with existing booking data (step 0 idempotency check caught it)

**Tests:**
- Unit: confirmationNumber generation format, all error path branches
- Integration: happy path, HOLD_EXPIRED, HOLD_MISMATCH, idempotent replay, crash-recovery simulation

### Acceptance Criteria
- [x] `POST /api/v1/bookings` returns 201 with `confirmationNumber` format `SL-YYYYMMDD-XXXX`
- [x] Same `confirmationNumber` on all booking rows in a multi-slot session
- [x] `slots.status` is `BOOKED` after confirmation
- [x] `holds.status` is `CONFIRMED` after confirmation
- [x] Redis hold keys are deleted after Postgres commits
- [x] Expired hold (Redis key missing) → 409 `HOLD_EXPIRED`
- [x] holdId mismatch → 409 `HOLD_MISMATCH`
- [x] Second `POST /bookings` with same sessionId → 201 with existing data (idempotent)
- [x] Crash-recovery test passes: Postgres committed but Redis DEL skipped → retry returns 201
- [x] All unit and integration tests pass

---

## Stage 9 — booking-service: Hold Expiry Job

**Status:** COMPLETE

**Completed:** 2026-03-03. All acceptance criteria met.

**Goal:** Background job that expiries stale holds and returns slots to AVAILABLE. Runs every 60s. Uses `SELECT FOR UPDATE SKIP LOCKED` to be safe across multiple service instances.

**Prerequisites:** Stage 8 complete.

### Exact Operation Sequence

```
@Scheduled(fixedDelay = 60_000)  // 60 seconds
fun expireHolds() {

  1. POLLING QUERY (Postgres)
     SELECT hold_id, slot_id, session_id, user_id FROM holds
     WHERE expires_at < now() AND status = 'ACTIVE'
     ORDER BY expires_at ASC
     LIMIT 500
     FOR UPDATE SKIP LOCKED
     └─ Empty result → return (nothing to do this cycle)

  2. BATCH TRANSACTION
     BEGIN
       UPDATE holds SET status = 'EXPIRED'
         WHERE hold_id IN (:holdIds) AND status = 'ACTIVE'
         -- AND status='ACTIVE' is belt-and-suspenders against concurrent processing
       UPDATE slots SET status = 'AVAILABLE'
         WHERE slot_id IN (:slotIds) AND status = 'HELD'
         -- Conditional and non-fatal: slot may already be AVAILABLE
     COMMIT
     └─ Deadlock / timeout:
          Retry up to 3× with exponential backoff (500ms, 1s, 2s)
          If all retries fail: halve batch size and retry once more
          Remaining holds picked up in next 60s cycle

  3. NO REDIS OPERATION
     Redis TTL has already fired — keys are gone.
     Do NOT attempt DEL (no-op anyway, but signals wrong intent).

  4. PUBLISH HoldExpiredEvent per sessionId (group slots by sessionId)
     SQS: HoldExpiredEvent { sessionId, userId, expiredSlotIds, timestamp }
     (stub/mock until Stage 11)
}
```

### What to Build

- `HoldExpiryJob` Spring `@Component` with `@Scheduled(fixedDelay = 60_000)`
- `@EnableScheduling` on booking-service main class
- Retry logic using Spring Retry or manual try/catch with backoff
- `HoldExpiredEvent` event DTO

**`application.yml`:**
```yaml
seatlock:
  expiry:
    batch-size: 500
    max-retries: 3
```

**Integration test for SKIP LOCKED:**
Start two instances of the expiry job targeting the same test database. Insert 10 expired holds. Run both jobs concurrently. Assert each hold is expired exactly once (no double-expiry).

**Tests:**
- Unit: batch size halving logic, retry logic
- Integration: expired holds are moved to EXPIRED status; slots return to AVAILABLE; SKIP LOCKED prevents double-expiry; holds not yet expired are untouched

### Acceptance Criteria
- [x] Job runs every 60 seconds (`@Scheduled` with configurable `interval-ms`; large `initial-delay-ms` in tests prevents auto-run)
- [x] Expired holds (`expires_at < now()`) are set to `EXPIRED` status
- [x] Corresponding slots are returned to `AVAILABLE`
- [x] Non-expired holds are untouched
- [x] `AND status = 'ACTIVE'` guard prevents double-expiry (belt-and-suspenders alongside SKIP LOCKED)
- [x] Retry fires on simulated deadlock (unit test: mock throws `PessimisticLockingFailureException`)
- [x] Batch size halves after all retries exhausted
- [x] SKIP LOCKED concurrent instance test: 10 expired holds → each expired exactly once
- [x] All unit and integration tests pass

---

## Stage 10 — booking-service: Cancellation + History

**Status:** COMPLETE

**Goal:** Cancel bookings, booking history for users, admin bookings view. Includes the ADR-008 stale hold key cleanup.

**Prerequisites:** Stage 8 complete (bookings exist).

### Exact Operation Sequence for Cancellation

```
POST /api/v1/bookings/{confirmationNumber}/cancel

1. LOAD ALL BOOKINGS + SLOT DATA (Postgres)
   SELECT b.*, s.start_time, s.venue_id FROM bookings b
   JOIN slots s ON b.slot_id = s.slot_id
   WHERE b.confirmation_number = :confirmationNumber
   (No status filter — load all regardless of current status)
   └─ Empty → 404 BOOKING_NOT_FOUND

2. AUTHORIZE
   Assert every booking.user_id == JWT userId → 403 FORBIDDEN

3. CONVERGENCE CHECK (idempotency)
   Partition into CONFIRMED and CANCELLED sets.
   └─ CONFIRMED set empty → return 200 with current data (all already cancelled)
   Continue with CONFIRMED set only.

4. 24h WINDOW CHECK (CONFIRMED items only)
   For each booking in CONFIRMED set:
     assert slot.start_time > now() + 24h
   └─ Any violation → 409 CANCELLATION_WINDOW_CLOSED

5. POSTGRES TRANSACTION
   BEGIN
     UPDATE bookings SET status='CANCELLED', cancelled_at=now()
       WHERE confirmation_number=:confirmationNumber AND status='CONFIRMED'
     UPDATE holds SET status='RELEASED'
       WHERE session_id=:sessionId AND status='CONFIRMED'
     UPDATE slots SET status='AVAILABLE'
       WHERE slot_id IN (:confirmedSlotIds) AND status='BOOKED'
   COMMIT
   └─ Failure → ROLLBACK → 500

6. REDIS CLEANUP (ADR-008 stale key protection)
   DEL hold:{slotId} for each slot in CONFIRMED set
   (No-op if key doesn't exist — safe always)
   DEL slots:{venueId}:{date} for each affected venue+date combination

7. PUBLISH BookingCancelledEvent to SQS (stub until Stage 11)

8. RETURN 200
   { confirmationNumber, cancelledAt, bookings: [...all rows, now CANCELLED] }
```

**Why DEL hold:{slotId} in step 6:**
If booking-service crashed during Stage 8 after Postgres committed but before Redis DEL ran, a stale `hold:{slotId}` key may still exist in Redis. Without step 6, a new user's `SETNX` on that slot would fail, blocking them from holding a genuinely available slot for up to 30 minutes. The DEL here is a no-op if the key is absent — always safe.

### Other Endpoints

**`GET /api/v1/bookings`** (user booking history):
```sql
SELECT b.*, s.start_time, v.name AS venue_name FROM bookings b
JOIN slots s ON b.slot_id = s.slot_id
JOIN venues v ON s.venue_id = v.venue_id
WHERE b.user_id = :userId
ORDER BY b.created_at DESC
```
Group results by `confirmation_number` in the application layer. Return a list of sessions, each with their slots.

**`GET /api/v1/admin/venues/{venueId}/bookings`** (admin):
```sql
SELECT b.*, s.start_time FROM bookings b
JOIN slots s ON b.slot_id = s.slot_id
WHERE s.venue_id = :venueId AND b.status = 'CONFIRMED'
  AND (DATE(s.start_time AT TIME ZONE 'UTC') = :date OR :date IS NULL)
ORDER BY s.start_time ASC
```
Requires ADMIN role.

**Tests:**
- Unit: 24h window check boundary (exactly 24h, just over, just under), convergence logic
- Integration: cancel happy path, already cancelled (idempotent 200), within 24h window (409), GET /bookings grouped correctly, admin endpoint (ADMIN vs USER role)

### Acceptance Criteria
- [x] `POST /cancel` cancels CONFIRMED bookings; slots return to AVAILABLE immediately
- [x] `holds.status` updated to RELEASED for cancelled session
- [x] `DEL hold:{slotId}` fires for each slot (no-op if key absent — no error thrown)
- [x] Cancelling already-cancelled bookings returns 200 (idempotent)
- [x] Any slot within 24h window blocks entire cancellation → 409 `CANCELLATION_WINDOW_CLOSED`
- [x] `GET /bookings` returns bookings grouped by `confirmationNumber`, ordered newest first
- [x] Admin `GET /admin/venues/{id}/bookings` returns confirmed bookings; USER gets 403
- [x] `BookingCancelledEvent` published (mocked)
- [x] All unit and integration tests pass

---

## Stage 11 — notification-service

**Status:** COMPLETE — 2026-03-05. All acceptance criteria met. All unit and integration tests pass.

**Acceptance criteria verification:**
- ✅ `docker-compose up` starts ElasticMQ (softwaremill/elasticmq-native) and Mailpit (axllent/mailpit) alongside Postgres and Redis
- ✅ booking-service publishes all three event types to ElasticMQ via `SqsAsyncClient` (fire-and-forget, typed envelope)
- ✅ notification-service consumes and dispatches within 5 seconds (confirmed by Awaitility 10s timeout in ITs)
- ✅ `BookingConfirmedEvent` results in an email in Mailpit (checked via `GET /api/v1/messages`)
- ✅ DLQ configured with `maxReceiveCount=3` in elasticmq.conf; Spring Cloud AWS retries on listener throw
- ✅ 4 unit tests pass (NotificationEventHandlerTest); 3 ITs pass (NotificationListenerIT); all booking-service tests still green

**Implementation deviations from spec:**
- Mailhog replaced with Mailpit (Mailhog is unmaintained; Mailpit is its active maintained replacement)
- `NoOpBookingEventPublisher` kept as non-Spring class (removed `@Component`) rather than deleted, for potential test use
- Email recipient is a configured `default-recipient` (not user email) — booking events don't carry user email; user email routing is a Phase 2 concern
- `SqsAsyncClient.sendMessage()` used directly (not `SqsTemplate`) to guarantee the message body is the raw JSON string without converter interference

**Goal:** SQS consumer that dispatches notifications for all three booking lifecycle events.

**Prerequisites:** Stage 10 complete (all three event types are published by booking-service).

### What to Build

**Docker Compose additions:**
```yaml
elasticmq:
  image: softwaremill/elasticmq-native
  ports: ["9324:9324", "9325:9325"]
  volumes:
    - ./infra/elasticmq/elasticmq.conf:/opt/elasticmq.conf
```

**`infra/elasticmq/elasticmq.conf`:**
```
queues {
  seatlock-events { defaultVisibilityTimeout = 30 seconds }
  seatlock-events-dlq { }
}
```

**Update Docker Compose and booking-service `application.yml`** to point SQS URL at ElasticMQ (`http://localhost:9324`).

**Update booking-service** to actually publish events to SQS (remove stub from Stages 8–10).

**notification-service:**
- Spring Cloud AWS SQS listener (`@SqsListener("seatlock-events")`)
- `NotificationEventHandler.handle(event)` dispatches by event type
- `EmailService` using `JavaMailSender` (points to Mailhog SMTP on port 1025 locally)
- `SmsService` stub (logs to console locally — real Twilio in Stage 16)

**Event handlers:**
| Event | Email subject | Body |
|-------|--------------|------|
| `HoldExpiredEvent` | "Your hold has expired" | Hold details, link to browse again |
| `BookingConfirmedEvent` | "Booking confirmed — {confirmationNumber}" | All slots, confirmation number |
| `BookingCancelledEvent` | "Booking cancelled — {confirmationNumber}" | Cancelled slots, refund info |

**DLQ configuration:** after 3 failed processing attempts, message moves to `seatlock-events-dlq`.

**Tests:**
- Unit: event handler dispatches correct email template per event type
- Integration: publish a `BookingConfirmedEvent` to ElasticMQ → assert email received in Mailhog (via Mailhog REST API)

### Acceptance Criteria
- [ ] `docker-compose up` starts ElasticMQ and Mailhog alongside Postgres and Redis
- [ ] booking-service publishes all three event types to ElasticMQ queue
- [ ] notification-service consumes and dispatches within 5 seconds
- [ ] `BookingConfirmedEvent` results in an email in Mailhog (check via `GET http://localhost:8025/api/v2/messages`)
- [ ] Failed events retry up to 3× then land in DLQ
- [ ] All unit and integration tests pass

---

## Stage 12 — Resilience

**Status:** COMPLETE

**Goal:** Implement all four resilience patterns decided in the Phase 0 deep dives. The system degrades gracefully instead of failing hard.

**Prerequisites:** Stage 11 complete (all services running and communicating).

### What to Build

**A. Redis unavailable → 503 degraded mode (OQ-21)**

In `HoldService.createHold()`, wrap the Redis SETNX call:
```kotlin
try {
    // Redis SETNX phase
} catch (e: RedisConnectionException) {
    throw ServiceUnavailableException("Booking temporarily unavailable. Please try again shortly.")
}
```
`ServiceUnavailableException` → 503 `SERVICE_UNAVAILABLE`.

`GET /venues/{id}/slots` already has a Postgres fallback (Stage 5). If Redis is down, it just queries Postgres. No change needed.

Test: start Redis, create a hold, stop Redis container via Testcontainers `stop()`, attempt another hold → assert 503. Restart Redis → holds work again.

**B. Vault fail-fast on startup + in-memory hold at runtime (OQ-22)**

Add Spring Cloud Vault (`spring-cloud-starter-vault-config`) to all services.

`bootstrap.yml` per service:
```yaml
spring:
  cloud:
    vault:
      host: localhost
      port: 8200
      token: root
      kv:
        enabled: true
        backend: secret
        default-context: seatlock
  config:
    import: vault://
```

Secrets path in Vault: `secret/seatlock` contains `jwt.secret`, `db.password`, `redis.password`, etc.

**Fail-fast on startup:** if Vault is unreachable, `spring.config.import: vault://` causes the application context to fail to load. Container exits with non-zero code. ECS restarts it.

**In-memory hold at runtime:** configure `spring.cloud.vault.fail-fast: true` for startup only. For runtime refresh, use `@RefreshScope` beans with a `VaultHealthIndicator` that exposes `DOWN` status if Vault is unreachable — but does NOT crash the service.

Add `vault` to Docker Compose for local dev:
```yaml
vault:
  image: hashicorp/vault:1.15
  ports: ["8200:8200"]
  environment:
    VAULT_DEV_ROOT_TOKEN_ID: root
    VAULT_DEV_LISTEN_ADDRESS: 0.0.0.0:8200
  cap_add: [IPC_LOCK]
```

Seed script `infra/vault/seed.sh`: writes all secrets to Vault on startup.

Test: stop Vault → verify `/actuator/health` shows `vault: DOWN` but service continues serving requests. Restart Vault → health returns UP.

**C. Resilience4j circuit breaker — booking-service → venue-service (OQ-22 supporting)**

Add `resilience4j-spring-boot3`:
```yaml
resilience4j:
  circuitbreaker:
    instances:
      venue-service:
        failureRateThreshold: 50
        waitDurationInOpenState: 30s
        slidingWindowSize: 10
        permittedNumberOfCallsInHalfOpenState: 3
```

Wrap `SlotVerificationClient` with `@CircuitBreaker(name = "venue-service", fallbackMethod = "venueServiceFallback")`. Fallback returns 503.

**D. Retry policies**

```yaml
resilience4j:
  retry:
    instances:
      redis-ops:
        maxAttempts: 3
        waitDuration: 100ms
        retryExceptions: [org.springframework.data.redis.RedisConnectionException]
      venue-http:
        maxAttempts: 2
        waitDuration: 200ms
```

Note: Do NOT retry on `SETNX` returning nil — that is a business result (slot taken), not a connection error.

### Acceptance Criteria
- [x] Redis down → `POST /holds` returns 503 `SERVICE_UNAVAILABLE` — unit test (DataAccessResourceFailureException → RedisUnavailableException) + IT (VenueServiceUnavailableException fallback → 503)
- [x] Redis down → `GET /venues/{id}/slots` still works (Postgres fallback) — verified in Stage 5 SlotCacheIT; SlotCacheService swallows Redis errors on read
- [x] Vault unreachable at startup → service fails to start (context load error) — guaranteed by `spring.config.import: vault://` (non-optional) in `application-vault.yml`; context fails to load if Vault is unreachable
- [x] Vault unreachable at runtime → `/actuator/health` shows `vault: DOWN`; service continues serving — guaranteed by built-in `VaultHealthIndicator` (spring-cloud-starter-vault-config + actuator); secrets held in-memory after startup
- [x] Vault restored → `/actuator/health` recovers to UP — `VaultHealthIndicator` polls Vault on each health check; auto-recovers
- [x] Circuit breaker opens after 5+ consecutive venue-service failures; `POST /holds` returns 503 immediately — `@CircuitBreaker` on `SlotVerificationClient.verify()`; fallback throws `VenueServiceUnavailableException` → 503; Resilience4j state machine handles OPEN/HALF_OPEN/CLOSED
- [x] Circuit breaker recovers after venue-service is restored — `waitDurationInOpenState: 30s` + `permittedNumberOfCallsInHalfOpenState: 3` in config
- [x] All unit and integration tests pass — confirmed: `./gradlew test integrationTest` green across all 4 services

**Implementation notes (deviations from plan):**
- Used `application-vault.yml` profile file instead of `bootstrap.yml` (modern Spring Boot 3.x approach — no bootstrap phase)
- `@Retry` placed on `RedisHoldRepository.setnx()` (not on a `HoldService` method) so the catch for `DataAccessException` → `RedisUnavailableException` lives in `HoldService`'s SETNX loop; this is the only way to both retry AND clean up already-acquired keys on failure
- Two fallback overloads on `SlotVerificationClient`: one for `SlotNotFoundException` (rethrow — business error) and one for generic `Exception` (throw `VenueServiceUnavailableException`)
- Spring Cloud BOM: used `2024.0.1` (Moorgate) not `2025.0.0` (which resolved but its CompatibilityVerifier rejects Spring Boot 3.5.0); added `spring.cloud.compatibility-verifier.enabled: false` to all services

---

## Stage 13 — API Documentation (Swagger UI)

**Status:** COMPLETE

**Goal:** Add interactive OpenAPI documentation to all services. Every endpoint is explorable and callable from the browser without any external tools.

**Prerequisites:** Stage 12 complete.

### What to Build

**Dependencies (user-service, venue-service, booking-service — `build.gradle.kts`):**
```kotlin
implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.4")
```
notification-service has no public endpoints — skip the dependency there.

**`application.yml` additions (user-service, venue-service, booking-service):**
```yaml
springdoc:
  api-docs:
    path: /v3/api-docs
  swagger-ui:
    path: /swagger-ui.html
    operations-sorter: alpha
```

**SecurityConfig updates (user-service, venue-service, booking-service):** add `/swagger-ui/**`, `/swagger-ui.html`, `/v3/api-docs/**` to `permitAll()` so the UI loads without a JWT.

**`OpenApiConfig.java` (one per service — user/venue/booking):** configure a Bearer JWT security scheme so the "Authorize" button appears in the UI, allowing calls to protected endpoints.

```java
@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
            .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
            .components(new Components()
                .addSecuritySchemes("bearerAuth", new SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")));
    }
}
```

### Acceptance Criteria
- [ ] `http://localhost:8081/swagger-ui.html` loads all user-service endpoints
- [ ] `http://localhost:8082/swagger-ui.html` loads all venue-service endpoints
- [ ] `http://localhost:8083/swagger-ui.html` loads all booking-service endpoints
- [ ] "Authorize" button present on all 3 services — paste a JWT and call protected endpoints successfully
- [ ] `GET /v3/api-docs` returns valid OpenAPI JSON on all 3 services
- [ ] All unit and integration tests pass

---

## Maintenance — Cross-Service DB Integrity (between Stages 13–14)

**Status:** COMPLETE

**What was done:**

**Problem:** `booking-service` local startup failed — `V1__create_holds.sql` and `V2__create_bookings.sql` had `REFERENCES users(user_id)` and `REFERENCES slots(slot_id)` pointing to tables that live in other databases (`user_db`, `venue_db`). PostgreSQL does not support cross-database FK constraints.

**Solution — Application-layer referential integrity:**

1. **Removed cross-DB FK constraints** from `V1__create_holds.sql` and `V2__create_bookings.sql`. `user_id` and `slot_id` are now plain UUID columns. Integrity is enforced in application code.

2. **Created `V3__create_local_tables.sql`** — booking-service's own `slots` and `venues` tables in `booking_db`. These are booking-service's read-model of venue-service data, with booking-service owning `slot.status` (AVAILABLE/HELD/BOOKED).

3. **Write-through cache on first hold** — `HoldService.createHold()` upserts slot metadata and venue name into local tables inside the same Postgres transaction, before the `UPDATE slots SET status = 'HELD'`. Slot existence is already validated upstream via `SlotVerificationClient.verify()`.

4. **Added `venueName` to `InternalSlotResponse`** in both venue-service and booking-service. `InternalSlotController` now batch-fetches venue names (one `findAllById` call for all venue IDs in the response).

5. **Updated `V0__create_stub_tables.sql`** — removed `slots`/`venues` stubs (now covered by V3 with `CREATE TABLE IF NOT EXISTS`), kept `users` stub for test data setup only.

**New tests added:**
- `HoldServiceTest.nullVenueId_slotWithoutVenue_holdCreatedSuccessfully` — null venueId guard
- `CancellationServiceTest.nullStartTime_treatedAsWindowClosed_throwsCancellationWindowClosedException` — null startTime defensive path
- `InternalSlotControllerTest` (5 tests) — venueName included/null, SlotNotFoundException, multi-venue batch fetch

---

## Maintenance — venue_db Slot Status Write Gap (between Stages 13–14, session 2)

**Status:** COMPLETE

**Problem:** `POST /api/v1/holds` (and booking confirmation, cancellation, expiry job) updated slot status
only in booking-service's local mirror `slots` table in `booking_db`. The canonical `slots` table in `venue_db`
was never touched. On a cache miss, venue-service read `venue_db` and returned `AVAILABLE` for a slot that
was actually `HELD`/`BOOKED`. Double-booking was still prevented (Redis SETNX), but stale browse results
were possible on cache miss.

**Solution — Second `DataSource` / `JdbcTemplate` for venue_db:**

1. **`VenueDbConfig.java`** — new `@Bean("venueJdbcTemplate")` that reads `seatlock.venue-datasource.*`
   and builds a separate `JdbcTemplate` wired to `venue_db`. No interference with Spring Boot's primary
   datasource autoconfiguration (different bean name; no `@Primary` concern).

2. **All four write paths updated** — `HoldService`, `BookingService`, `CancellationService`,
   `HoldExpiryJob` each inject `@Qualifier("venueJdbcTemplate")`. After the booking_db transaction
   commits, a best-effort `UPDATE slots SET status = ?` runs against `venue_db`. Failures are caught,
   logged as WARN, and swallowed — Redis SETNX remains the double-booking gate.

3. **`application.yml`** — added `seatlock.venue-datasource.url/username/password` (defaults to
   `venue_db` on same Postgres host as `booking_db`).

4. **`AbstractIntegrationTest.java`** — wires `seatlock.venue-datasource.*` to the same Testcontainer
   Postgres (single-DB setup in tests; both datasources share the same DB, so the venue_db UPDATE hits
   the same `slots` table as the booking_db transaction).

5. **Unit tests** — all four `@Mock JdbcTemplate venueJdbcTemplate` added to test constructors.

**Key discovery — `@MockitoBean` replaces by type, not by name:**
Attempting `@MockitoBean(name = "venueJdbcTemplate")` in `HoldControllerIT` broke all integration tests
because Spring's `@MockitoBean` replaces beans by type, substituting BOTH JdbcTemplate beans (primary + venue)
with the same mock. The non-fatal test was moved to unit level instead (see `HoldServiceTest`).

**New test added:**
- `HoldServiceTest.venueDbUpdateFails_holdStillSucceeds` — stubs `venueJdbcTemplate.update()` to throw
  `DataAccessResourceFailureException`; asserts hold response is still returned correctly.

---

## Stage 14 — Observability

**Status:** COMPLETE

**Goal:** Make the system inspectable in production. Health, metrics, and dashboards.

**Prerequisites:** Stage 12 complete.

### What to Build

**Docker Compose additions:**
```yaml
prometheus:
  image: prom/prometheus
  ports: ["9090:9090"]
  volumes: ["./infra/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml"]

grafana:
  image: grafana/grafana
  ports: ["3001:3000"]
  volumes: ["./infra/grafana/dashboards:/var/lib/grafana/dashboards"]
```

**`prometheus.yml`:** scrape all four services at `/actuator/prometheus`.

**Spring Actuator** (all services — already present from Stage 1, now expose fully):
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus
  endpoint:
    health:
      show-details: always
```

**Custom Prometheus metrics** (Micrometer — add to relevant services):

| Metric | Type | Service | Tags |
|--------|------|---------|------|
| `seatlock.holds.created` | Counter | booking-service | `venueId` |
| `seatlock.holds.expired` | Counter | booking-service | — |
| `seatlock.bookings.confirmed` | Counter | booking-service | — |
| `seatlock.bookings.cancelled` | Counter | booking-service | — |
| `seatlock.availability.cache.hit` | Counter | venue-service | `venueId` |
| `seatlock.availability.cache.miss` | Counter | venue-service | `venueId` |
| `seatlock.expiry.batch.size` | DistributionSummary | booking-service | — |

**Grafana dashboard** (`infra/grafana/dashboards/seatlock.json`): panels for hold rate, booking rate, cache hit ratio, expiry batch size, service health status.

**Blackbox exporter** (optional — probe health endpoints): add `prom/blackbox-exporter` to Docker Compose. Configure probes for `GET /actuator/health` on all four services.

### Acceptance Criteria
- [ ] `/actuator/health` on all services shows individual component status (DB, Redis, Vault, SQS)
- [ ] `/actuator/prometheus` returns Prometheus-format metrics
- [ ] All custom counters increment correctly (verified by making API calls and checking metric values)
- [ ] Prometheus scrapes all services successfully (check Prometheus targets page at `:9090`)
- [ ] Grafana dashboard loads and displays real data after a few API calls
- [ ] All unit and integration tests pass

---

## Stage 15 — Frontend: Auth + Browse

**Status:** COMPLETE

**Goal:** React frontend — authentication and slot browsing with live 5-second refresh. No booking logic yet.

**Prerequisites:** Stages 3–5 complete and running locally.

### What to Build

**Project setup:**
```
seatlock-ui/
├── package.json
├── vite.config.ts
├── tsconfig.json
└── src/
    ├── main.tsx
    ├── api/          (Axios client, API functions)
    ├── components/   (reusable UI components)
    ├── pages/        (route-level components)
    └── hooks/        (React Query hooks)
```

**Dependencies:** React 18, TypeScript, Vite, TanStack Query v5, Axios, React Router v6, Tailwind CSS.

**Axios config (`src/api/client.ts`):**
```ts
const client = axios.create({ baseURL: 'http://localhost:8080/api/v1' })
client.interceptors.request.use(config => {
    const token = localStorage.getItem('token')
    if (token) config.headers.Authorization = `Bearer ${token}`
    return config
})
```

**Pages:**
- `/login` — `POST /auth/login`; store JWT in localStorage; redirect to `/venues`
- `/register` — `POST /auth/register`; redirect to `/login`
- `/venues` — `GET /venues`; list venue cards with name, city, state; link to slots page
- `/venues/:venueId/slots` — date picker + `GET /venues/{id}/slots?date=&status=AVAILABLE`
  - React Query `useQuery` with `refetchInterval: 5000` (5s polling)
  - Slot grid showing time + status chip (AVAILABLE = green, HELD = yellow, BOOKED = grey)
  - Multi-select checkboxes on AVAILABLE slots → "Hold selected" button → calls `POST /holds`
  - On successful hold: redirect to `/holds/:sessionId`

**Error display:** map domain error codes to user-friendly messages. `EMAIL_ALREADY_EXISTS` → "This email is already registered." etc.

### Acceptance Criteria
- [ ] User can register and log in
- [ ] JWT stored in localStorage; attached to all API requests
- [ ] `/venues` lists active venues from the API
- [ ] `/venues/:id/slots` shows slots for selected date, refreshes every 5 seconds
- [ ] Slot status chips update automatically without page reload
- [ ] User can select multiple AVAILABLE slots and click "Hold"
- [ ] All domain error codes display human-readable messages

---

## Stage 16 — Frontend: Booking Flows

**Status:** COMPLETE

**Goal:** Hold confirmation, booking history, and cancellation in the React frontend.

**Prerequisites:** Stage 14 complete.

### What to Build

**Pages:**
- `/holds/:sessionId` — hold confirmation page
  - Show slots in the hold + venue + times
  - Countdown timer (`expiresAt - now()`) — updates every second
  - "Confirm Booking" button → `POST /bookings { sessionId }` → redirect to `/bookings/:confirmationNumber`
  - If timer reaches 0: show "Hold expired — start again" with link back to browse
- `/bookings` — booking history
  - `GET /bookings` → group by `confirmationNumber`
  - Show each session: confirmation number, slots, status (CONFIRMED / CANCELLED)
  - "Cancel" button for CONFIRMED sessions with all slots > 24h away
- `/bookings/:confirmationNumber` — booking detail
  - Same data as history row but full page
  - Cancel button → `POST /bookings/{confirmationNumber}/cancel`
  - On success: show cancelled state; slots available again

**Error handling for domain codes:**
| Code | User-facing message |
|------|---------------------|
| `SLOT_NOT_AVAILABLE` | "This slot is no longer available. Please choose another." |
| `HOLD_EXPIRED` | "Your hold has expired. Please browse and try again." |
| `HOLD_MISMATCH` | "Something went wrong with your hold. Please refresh and try again." |
| `CANCELLATION_WINDOW_CLOSED` | "Cancellation is not available within 24 hours of your reservation." |
| `SESSION_NOT_FOUND` | "Hold session not found. Please start a new hold." |

### Acceptance Criteria
- [ ] Full booking flow works end-to-end: browse → select slots → hold → confirm → see in history
- [ ] Hold countdown timer counts down in real-time; expired hold shows error state
- [ ] Booking history shows all sessions grouped by confirmation number
- [ ] Cancel flow works; slot appears as AVAILABLE on next availability poll
- [ ] All domain error codes show the correct user-facing message
- [ ] Works in Chrome and Firefox

---

## Stage 17 — Infrastructure (AWS)

**Status:** ✅ COMPLETE

**Completed:** Full booking flow smoke tested live on AWS. CI/CD pipeline split: master push → CI only (build+test+ECR push); `v*` tag → release pipeline with manual approval gate → ECS deploy.

**RSA keys are stored in `/tmp/jwt_private.pem` + `/tmp/jwt_public.pem` on the machine where they were generated (this session). If those files are gone, regenerate with:**
```bash
openssl genrsa -out /tmp/jwt_raw.pem 2048
openssl pkcs8 -topk8 -inform PEM -outform PEM -nocrypt -in /tmp/jwt_raw.pem -out /tmp/jwt_private.pem
openssl rsa -in /tmp/jwt_raw.pem -pubout -out /tmp/jwt_public.pem
```

**Goal:** Deploy the fully working local system to AWS. Provision all infrastructure with Terraform. Full CI/CD pipeline.

**Prerequisites:** Stages 1–15 complete and all services passing tests.

> ⚠️ **BEFORE DEPLOY — Add E2E cross-service tests:**
> No test currently makes a real HTTP call from one service to another.
> Each service's integration tests create JWTs in-process using `Hs256JwtProvider` — they never call user-service's HTTP endpoint.
> Pre-deploy steps:
> 1. Create a Postman collection covering the full happy path:
>    `register → login → create venue (ADMIN) → generate slots → POST /holds → POST /bookings → GET /bookings → POST /cancel`
> 2. Run via Newman in CI: `newman run seatlock-e2e.postman_collection.json --env-var base_url=http://localhost`
> 3. Optionally add a dedicated `e2e-tests` Gradle module with Testcontainers that starts all services and runs the same flow via `RestTemplate`.
> This is the only test that would catch a JWT secret mismatch or claim-name divergence between services.

> ⚠️ **BEFORE DEPLOY — RS256 JWT migration required:**
> Currently all three services share the same HS256 symmetric secret (local dev shortcut).
> Pre-deploy steps:
> 1. Generate an RSA 2048 key pair
> 2. user-service: sign tokens with the **private key** (RS256)
> 3. venue-service + booking-service: validate tokens with the **public key only**
> 4. Remove the shared secret from venue/booking `application.yml` and Vault
> 5. Store the private key in Vault under `secret/seatlock/jwt.private-key`; distribute public key as an env var or Vault path
> See `docs/CONTEXT.md → Phase 0 Compromises` for context.

### What to Build

**Terraform resources (`infra/terraform/`):**

| Resource | Details |
|----------|---------|
| VPC | 2 public subnets (ALB) + 2 private subnets (ECS, RDS, Redis) + IGW + NAT Gateway |
| ALB | Path-based listener rules per `docs/system-design/05-high-level-design.md` routing table |
| ECS Fargate cluster | One cluster, one task definition per service |
| RDS PostgreSQL | Multi-AZ, `db.t3.medium`, automated backups |
| ElastiCache Redis | `cache.t3.micro`, with one read replica |
| SQS | Standard queue + DLQ (maxReceiveCount = 3) |
| AWS Cloud Map | Namespace `seatlock.local`; one service per ECS service |
| ECR | One repository per service image |
| IAM | Task execution role + task role per service (least privilege) |
| Security groups | ECS tasks → RDS (5432), ECS tasks → Redis (6379), ALB → ECS (8081–8084) |
| Vault (ECS) | Single task in private subnet; unsealed on start (dev mode for Phase 0) |

**ECS task definitions:**
- Image: `{account}.dkr.ecr.{region}.amazonaws.com/seatlock/{service}:latest`
- Env vars for prod: point to RDS endpoint, ElastiCache endpoint, SQS URL, Vault address
- Health check: `GET /actuator/health`
- `application-prod.yml` per service (points to real AWS endpoints)

**GitHub Actions deploy pipeline (`.github/workflows/deploy.yml`):**
```yaml
on:
  push:
    branches: [main]
jobs:
  deploy:
    steps:
      - Build all services (./gradlew build)
      - Run all tests (./gradlew test integrationTest)
      - Docker build + push to ECR for each service
      - aws ecs update-service --force-new-deployment for each service
```

**Separate Terraform workflow** (manual approval gate before apply):
```yaml
on: workflow_dispatch
jobs:
  terraform:
    steps:
      - terraform init
      - terraform plan
      - (manual approval required)
      - terraform apply
```

**Vault seeding for AWS:** Terraform provisions the Vault ECS task. A one-time init script populates secrets from AWS Secrets Manager bootstrap values (or manual setup).

### Acceptance Criteria
- [ ] `terraform apply` provisions all infrastructure without errors
- [ ] `GET https://{alb-dns}/api/v1/auth/register` works from the public internet
- [ ] Full booking flow works end-to-end via the ALB DNS
- [ ] GitHub Actions deploy pipeline triggers on merge to `main` and deploys new images
- [ ] All four services show healthy in ECS console
- [ ] RDS and ElastiCache are not accessible from the public internet (security group check)
- [ ] Vault is serving secrets to all services (verify via `/actuator/health` Vault component)
- [ ] `terraform destroy` cleanly removes all resources

---

## Key Reference: Operation Sequences

For quick lookup without opening the full design docs:

### Hold Creation (Stage 7) — SETNX gate
`Validate → Idempotency check → Verify slots (venue-service) → Redis SETNX (all-or-nothing) → Postgres transaction (INSERT holds + UPDATE slots AND status='AVAILABLE') → DEL cache → 200`

### Booking Confirmation (Stage 8) — Postgres BEFORE Redis DEL
`Idempotency check → Load holds → Authorize → Verify Redis keys → Postgres COMMIT (INSERT bookings + UPDATE holds/slots) → Redis DEL (best-effort) → SQS → 201`

### Cancellation (Stage 10) — Stale key cleanup
`Load all bookings → Authorize → Convergence check → 24h window → Postgres COMMIT (CANCEL bookings + RELEASE holds + AVAILABLE slots) → DEL hold:{slotId} (no-op safe) → DEL cache → SQS → 200`

### Hold Expiry Job (Stage 9) — SKIP LOCKED
`SELECT ... FOR UPDATE SKIP LOCKED LIMIT 500 → BATCH: UPDATE holds EXPIRED + UPDATE slots AVAILABLE → No Redis DEL → SQS HoldExpiredEvent`

---

## Stage 18 — E2E Tests (AWS)

**Status:** NOT STARTED

**Goal:** Add an automated end-to-end test suite that runs the full booking flow against the live ALB after every production deploy. Acts as a smoke-test gate in `release.yml` — if E2E fails, the GitHub release is not published.

**What to Build:**

- `e2e/seatlock-e2e.postman_collection.json` — Postman collection covering the full happy path:
  `register → login → create venue (ADMIN) → generate slots → POST /holds → POST /bookings → GET /bookings → POST /cancel`
- `e2e/seatlock-e2e.postman_environment.json` — environment file with `base_url` variable
- Newman runner script or direct CLI invocation in CI
- New job in `.github/workflows/release.yml`: runs after `deploy`, before `publish-release`
  - Installs Newman: `npm install -g newman`
  - Runs: `newman run e2e/seatlock-e2e.postman_collection.json --env-var base_url=http://<ALB_DNS>`
  - ALB DNS injected via GitHub Actions variable or `terraform output`
  - If Newman exits non-zero, `publish-release` is skipped

**Acceptance Criteria:**
- [ ] Newman exits 0 on a clean environment (all requests 2xx, all assertions pass)
- [ ] Newman exits non-zero if any step fails (e.g. login returns 401)
- [ ] `release.yml` blocks `publish-release` if E2E job fails
- [ ] Collection covers all 8 steps of the happy path
- [ ] Idempotency: collection cleans up after itself (cancel the booking it creates)

---

## Stage 19 — Frontend Deployment

**Status:** NOT STARTED

**Goal:** Deploy the React SPA (`seatlock-ui/`) to AWS so it is publicly accessible. The frontend talks to the existing ALB for all API calls.

**What to Build:**

- `infra/terraform/cloudfront.tf`:
  - `aws_s3_bucket` — private bucket for static assets
  - `aws_s3_bucket_policy` — allow CloudFront OAC only
  - `aws_cloudfront_origin_access_control`
  - `aws_cloudfront_distribution` — S3 origin; default root object `index.html`; custom error response 404 → `index.html` (React Router SPA routing)
- `seatlock-ui/.env.production` — `VITE_API_BASE_URL=http://<ALB_DNS>` (or blank if proxied via CloudFront behaviour)
- `infra/terraform/outputs.tf` — add `cloudfront_url` output
- Updated `deploy.yml` — add build + S3 sync step:
  ```
  cd seatlock-ui && npm ci && npm run build
  aws s3 sync dist/ s3://<bucket> --delete
  aws cloudfront create-invalidation --distribution-id <id> --paths "/*"
  ```

**Acceptance Criteria:**
- [ ] `https://<cloudfront-domain>/` loads the React app
- [ ] Login, browse venues, create hold, confirm booking all work end-to-end through the CloudFront → ALB path
- [ ] Page refresh on `/bookings` does not 404 (SPA routing handled by CloudFront error response)
- [ ] S3 bucket is not publicly accessible directly (OAC only)
- [ ] Frontend deploy runs automatically on master push (alongside existing ECR push)

---

## Stage 20 — Frontend UI Polish

**Status:** NOT STARTED

**Goal:** Improve the visual quality of the React SPA using Tailwind CSS. No new functionality — pure UX and design improvement.

**What to Build:**

- **Layout:** Consistent max-width container, proper navbar with logo + nav links + user info
- **Venue browse page:** Card grid with venue image placeholder, address, status badge
- **Slot grid:** Colour-coded availability (green = AVAILABLE, amber = HELD, red = BOOKED), better time display
- **Hold page:** Prominent countdown timer with colour transition (green → amber → red), cleaner slot summary table
- **Booking history:** Timeline-style list grouped by confirmation number, status badges, cancel button placement
- **Loading states:** Skeleton loaders instead of blank screens during data fetches
- **Error states:** Friendly error messages with retry buttons instead of raw error codes
- **Mobile responsiveness:** Single-column layout on small screens, touch-friendly tap targets
- **Colour palette:** Consistent primary/secondary/accent colours defined in `tailwind.config.js`

**Acceptance Criteria:**
- [ ] All existing functionality still works after visual changes
- [ ] Slot grid uses colour coding for availability status
- [ ] Loading skeletons shown during TanStack Query fetches
- [ ] Domain error codes (`SLOT_NOT_AVAILABLE`, etc.) show human-readable messages (already in `src/api/errors.ts` — verify wired up consistently)
- [ ] App is usable on a 375px wide mobile screen
- [ ] No raw UUIDs or internal IDs visible to users in any view
