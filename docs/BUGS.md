# SeatLock — Bug Log

Brief record of significant bugs and their fixes. Add new entries at the top.

---

## [2026-03-11] Admin seed migration accidentally committed with plaintext password

**Stage:** Stage 17 (AWS Infrastructure)

**Symptom:**
`V2__seed_admin.sql` was staged and committed locally. The file contained a SQL comment with the plaintext password (`-- Password: SeatLockAdmin2026!`) making the credential visible in git history even though the BCrypt hash alone would be acceptable.

**Root cause:**
Admin user seeding via Flyway migration is the wrong approach — any credential (even a hash) committed to git is a permanent record. The plaintext comment made it worse.

**Fix:**
`git reset --hard HEAD~1` before pushing (commit never reached remote). Used AWS CloudShell in VPC mode instead: attached to private subnet, installed psql, connected to RDS directly, ran a one-time `UPDATE users SET password_hash = '...' WHERE email = 'admin@seatlock.com'`.

**Rule established:** Never seed credentials via Flyway migrations. Use CloudShell VPC mode + psql for one-time bootstrap operations against private RDS.

**Files changed:** None (commit was reverted before push).

---

## [2026-03-08] Notification email exposes internal `sessionId` to users

**Stage:** Stage 16 (Frontend + Notifications review)

**Symptom:**
Booking confirmed email body contained `"Session ID: <UUID>"`. Users can see the internal `sessionId` field, which is meaningless to them and represents an unintended information disclosure.

**Root cause:**
`EmailService.sendBookingConfirmed()` included `"Session ID: " + event.sessionId()` appended to the email body. `sendHoldExpired()` also appended `"Session ID: " + event.sessionId()` instead of displaying the slot count.

**Fix:**
Removed `sessionId` from both email bodies. Booking confirmed email now shows only `confirmationNumber` and `slotIds.size()`. Hold expired email shows only the slot count (with correct singular/plural).

```java
public void sendBookingConfirmed(BookingConfirmedEvent event) {
    send("Booking confirmed — " + event.confirmationNumber(),
         "Your booking is confirmed.\n\n" +
         "Confirmation number: " + event.confirmationNumber() + "\n" +
         "Slots booked: " + event.slotIds().size());
}
```

**Files changed:** `notification-service/src/main/java/com/seatlock/notification/service/EmailService.java`

**Rule going forward:** Never include internal identifiers (sessionId, holdId, internal UUIDs) in user-facing notification content. Only include user-facing references (confirmationNumber) and human-readable summaries.

---

## [2026-03-08] `VenueDbConfig` suppresses Spring Boot's auto-configured `JdbcTemplate`, both injections hit venue_db

**Stage:** Bug found during Stage 14 prep (hold endpoint 500 error)

**Symptom:**
`POST /api/v1/holds` returns 500 with `DataIntegrityViolationException: null value in column "address" of relation "venues"`. The `address` column does not exist in booking-service's local `venues` table (V3 migration) — only in venue-service's full schema.

**Root cause:**
`VenueDbConfig` declares a `@Bean JdbcTemplate venueJdbcTemplate(...)`. Spring Boot's `JdbcTemplateAutoConfiguration` has `@ConditionalOnMissingBean(JdbcOperations.class)` on its `jdbcTemplate` bean method. Since `JdbcTemplate` implements `JdbcOperations`, the existence of `venueJdbcTemplate` satisfies that condition and Spring Boot **skips creating the auto-configured primary JdbcTemplate**. As a result, `HoldService`'s unqualified `JdbcTemplate jdbcTemplate` injection receives `venueJdbcTemplate` (the only JdbcTemplate bean in context), which points to `venue_db` instead of `booking_db`. The venues upsert hits venue_db's full-schema `venues` table, triggering the NOT NULL violation.

**Fix:**
Add an explicit `@Primary @Bean("jdbcTemplate")` in `VenueDbConfig` that wraps the auto-configured `DataSource` (booking_db). This ensures two distinct JdbcTemplate beans exist: the primary one for booking_db, and `venueJdbcTemplate` for venue_db.

```java
@Primary
@Bean("jdbcTemplate")
public JdbcTemplate bookingJdbcTemplate(DataSource dataSource) {
    return new JdbcTemplate(dataSource);
}
```

**Files changed:** `booking-service/.../config/VenueDbConfig.java`

---

## [2026-03-07] Spring `@MockitoBean` replaces ALL beans of matching type, not just by name

**Stage:** Maintenance — venue_db slot status write gap (session 2)

**Symptom:**
Adding `@MockitoBean(name = "venueJdbcTemplate") JdbcTemplate venueJdbcTemplate` to `HoldControllerIT`
caused all existing tests to fail with 409 CONFLICT (hold requests rejected). The new failure test returned
500 instead of the expected 200.

**Root cause:**
Spring's `@MockitoBean` resolves beans **by type first**, then registers the mock under the given name.
When there are two `JdbcTemplate` beans (primary auto-configured + `venueJdbcTemplate`), `@MockitoBean`
replaces **both** with the same Mockito mock. The primary `jdbcTemplate` inside the booking_db transaction
then returned `0` for `UPDATE slots SET status = 'HELD'` → row count mismatch → `SlotNotAvailableException`
→ 409.

**Fix:**
Do not use `@MockitoBean` when multiple beans of the same type exist in the context. Move the non-fatal
failure test to the unit test layer (`HoldServiceTest`) where the two `JdbcTemplate` mocks are separate
`@Mock`-annotated fields injected directly into the service constructor — no Spring context involved.

**Rule going forward:**
`@MockitoBean` is type-based. If you have two beans of the same class, only use `@MockitoBean` when you
want ALL of them replaced. For selective mocking, use unit tests with manual mock injection.

**Files changed:**
- `booking-service/src/integrationTest/…/controller/HoldControllerIT.java` — `@MockitoBean venueJdbcTemplate` removed
- `booking-service/src/test/java/com/seatlock/booking/service/HoldServiceTest.java` — `venueDbUpdateFails_holdStillSucceeds` added

---

## [FIXED — Phase 1] booking-service does not update venue_db slots.status on hold creation

**Stage:** Phase 0 known gap — to fix before Stage 15 (Frontend) at the latest.

**Symptom:**
`POST /api/v1/holds` creates a hold row in booking_db and updates the local mirror
`slots` table in booking_db, but the canonical `slots` table in **venue_db** is never
updated. On a Redis cache miss, venue-service reads venue_db and returns `AVAILABLE`
for a slot that is actually `HELD`.

**Root cause:**
ADR-006 intended booking-service to write `slots.status` in the shared Postgres cluster.
In practice, booking-service's `JdbcTemplate` is wired to `booking_db` only, so it can
never reach venue_db. The `UPDATE slots SET status = 'HELD'` in `HoldService` Step 6
only affects the local booking_db mirror, not the canonical table.

**Impact:**
- Double-booking: **not possible** — Redis SETNX is the primary gate and is unaffected.
- Stale browse results on cache miss: users may see `AVAILABLE` for a held slot until
  either the cache TTL expires (5s) or the slot's Redis key expires (30 min).

**Fix (TODO — Phase 1):**
Give booking-service a second `JdbcTemplate` / `DataSource` bean wired to venue_db's
datasource and use it specifically for the `UPDATE slots SET status = 'HELD'` (hold
creation) and `UPDATE slots SET status = 'AVAILABLE'/'BOOKED'` (confirm / cancel /
expiry job). All four write paths in HoldService, BookingService, CancellationService,
and HoldExpiryJob need updating.
Works on both local (single Postgres container, multiple DBs) and AWS production
(single RDS instance, multiple DBs) — same host, different database.

**Permanent fix (Phase 2 — service boundary redesign):**
The root cause is that `slots` is split across two service boundaries. The clean solution
is to consolidate ownership:
- **venue-service** owns only venue *definitions* (`venues` table: name, address, city,
  state, status). It becomes a pure reference-data service.
- **booking-service** owns `slots` entirely — table moves to `booking_db`, slot
  generation endpoint moves to booking-service, all status transitions (AVAILABLE →
  HELD → BOOKED → AVAILABLE) happen in one service against one DB with no cross-service
  writes.
- `GET /api/v1/venues/{venueId}/slots` either moves to booking-service or venue-service
  proxies it via an internal call.
This eliminates the cross-DB write problem permanently and gives each service true
autonomy over its data. Requires a migration plan to move the `slots` table and update
the ALB routing rules.

---

## [2026-03-07] Mockito PotentialStubbingProblem: anyList() does not match Set argument

**Stage:** Maintenance — cross-service DB integrity (between Stages 13–14)

**Symptom:**
`InternalSlotControllerTest` tests for `venueName` fail with:
```
PotentialStubbingProblem: this stubbing was never used
  -> when(venueRepository.findAllById(anyList())).thenReturn(...)
```

**Root cause:**
`anyList()` matches only `java.util.List` instances. `InternalSlotController.getSlotsByIds()` collects venue IDs into a `Set<UUID>` via `Collectors.toSet()` before calling `venueRepository.findAllById(venueIds)`. When Mockito sees an actual `Set` argument against an `anyList()` stub, strict mode treats the stub as unused.

**Fix:**
Use `any()` (unconstrained matcher) instead of `anyList()`:
```java
when(venueRepository.findAllById(any())).thenReturn(List.of(venue));
```

**Files changed:**
- `venue-service/src/test/java/com/seatlock/venue/controller/InternalSlotControllerTest.java` — 4 stubs changed from `anyList()` to `any()`

**Rule going forward:**
When stubbing `findAllById(Iterable)` or any method receiving a `Set`, use `any()` not `anyList()`. Use `anyList()` only when the actual runtime argument is definitely a `List`.

---

## [2026-03-07] PSQLException: Can't infer SQL type for java.time.Instant in JdbcTemplate

**Stage:** Maintenance — cross-service DB integrity (between Stages 13–14)

**Symptom:**
Integration tests fail with:
```
PSQLException: Can't infer the SQL type to use for an instance of java.time.Instant.
Use setObject() with an explicit Types value to specify the type to use.
```

**Root cause:**
`jdbcTemplate.update("INSERT INTO slots (slot_id, venue_id, status, start_time) VALUES (?, ?, 'AVAILABLE', ?)", slot.slotId(), slot.venueId(), slot.startTime())` passes a raw `java.time.Instant` as the 3rd parameter. PostgreSQL JDBC driver 42.7.5 calls `ps.setObject(index, instant)` but cannot infer an SQL type from a plain `Instant` object.

**Fix:**
Convert `Instant` to `java.sql.Timestamp` before passing to JdbcTemplate:
```java
java.sql.Timestamp startTime = slot.startTime() != null
        ? java.sql.Timestamp.from(slot.startTime()) : null;
jdbcTemplate.update("INSERT INTO slots ... VALUES (?, ?, 'AVAILABLE', ?)",
        slot.slotId(), slot.venueId(), startTime);
```

**Files changed:**
- `booking-service/src/main/java/com/seatlock/booking/service/HoldService.java`

**Rule going forward:**
Whenever passing a temporal value to `JdbcTemplate.update(String, Object...)`, always convert `Instant` → `java.sql.Timestamp.from()` and `LocalDate` → `java.sql.Date.valueOf()`. The JDBC driver cannot auto-detect types from java.time objects via `setObject()`.

---

## [2026-03-07] Flyway cross-database FK constraint — booking_db cannot reference user_db or venue_db

**Stage:** Maintenance — cross-service DB integrity (between Stages 13–14)

**Symptom:**
booking-service fails to start locally with Flyway error:
```
ERROR: relation "users" does not exist
```
`V1__create_holds.sql` contained `REFERENCES users(user_id)` and `REFERENCES slots(slot_id)`, but in the local Docker Compose setup each service has its own separate database. `booking_db` has no `users` or `slots` tables.

**Root cause:**
PostgreSQL does not support cross-database foreign key constraints. The design doc's Phase 0 compromise ("shared Postgres cluster") means the databases are logically separate schemas/databases on the same cluster, not a single database. Flyway runs migrations against `booking_db` only; `users` and `slots` live in `user_db` and `venue_db` respectively.

**Fix:**
1. Remove cross-DB FK constraints from `V1__create_holds.sql` and `V2__create_bookings.sql` — `user_id` and `slot_id` become plain `UUID NOT NULL` columns.
2. Create `V3__create_local_tables.sql` with booking-service's own `venues` and `slots` tables in `booking_db` (within-DB FK `slots.venue_id → venues.venue_id` is safe).
3. Enforce referential integrity at the application layer: upsert venue/slot metadata in `HoldService.createHold()` Step 6 before the `UPDATE slots SET status = 'HELD'`.

**Files changed:**
- `booking-service/src/main/resources/db/migration/V1__create_holds.sql` — removed `REFERENCES users(user_id)`, `REFERENCES slots(slot_id)`
- `booking-service/src/main/resources/db/migration/V2__create_bookings.sql` — removed cross-DB FK constraints
- `booking-service/src/main/resources/db/migration/V3__create_local_tables.sql` (new)
- `booking-service/src/test/resources/db/migration/V0__create_stub_tables.sql` — removed venues/slots stubs (now V3)
- `booking-service/src/main/java/com/seatlock/booking/service/HoldService.java` — added upsert in Step 6

**Rule going forward:**
PostgreSQL FKs can only reference tables in the same database. For cross-service data (user_id, slot_id), store the UUID as a plain NOT NULL column and enforce integrity at the application layer with write-through upserts or event-driven sync.

---

## [2026-03-06] Spring Cloud BOM 2025.0.0 rejects Spring Boot 3.5.0 — CompatibilityVerifier failure

**Stage:** 12 (Resilience + Vault)

**Symptom:**
All integration tests fail at context load with:
```
Spring Boot [3.5.0] is not compatible with this Spring Cloud release train.
Change Spring Boot version to one of the following versions [3.2.x, 3.3.x]
```

**Root cause:**
`spring-cloud-dependencies:2025.0.0` resolves successfully and its Vault starter compiles. However, `CompatibilityVerifierAutoConfiguration` (which runs unconditionally) only whitelists Spring Boot 3.2.x and 3.3.x — it hard-rejects 3.5.0 at startup.

**Fix:**
Downgrade BOM to `2024.0.1` (Moorgate release train — supports Spring Boot 3.3.x) and add `spring.cloud.compatibility-verifier.enabled: false` to all four `application.yml` files to suppress the version warning at runtime.

**Files changed:**
- `user-service/build.gradle.kts`, `venue-service/build.gradle.kts`, `booking-service/build.gradle.kts`, `notification-service/build.gradle.kts` — BOM version `2025.0.0` → `2024.0.1`
- All four `application.yml` files — added `spring.cloud.compatibility-verifier.enabled: false`

**Rule going forward:**
When adding Spring Cloud dependencies to a Spring Boot 3.5.x project, use BOM `2024.0.1` and always add `spring.cloud.compatibility-verifier.enabled: false`.

---

## [2026-03-06] Spring Cloud Vault auto-configures even without vault profile active

**Stage:** 12 (Resilience + Vault)

**Symptom:**
After adding `spring-cloud-starter-vault-config` to all four services' build files, all 23 integration tests fail with:
```
java.lang.IllegalStateException at ClientAuthenticationFactory.java:438
```
Vault token auth fails because Vault is not running in the test environment.

**Root cause:**
Spring Cloud Vault registers auto-configuration beans unconditionally when the JAR is on the classpath — it does not check the active profile. Even with no `vault` profile active, the `VaultAutoConfiguration` runs and attempts to authenticate with a Vault server.

**Fix:**
Add `spring.cloud.vault.enabled: false` to all four services' `application.yml` (the default). The `application-vault.yml` profile file sets `spring.cloud.vault.enabled: true`. Now Vault is active only when the `vault` Spring profile is explicitly activated.

**Files changed:**
- All four `application.yml` files — merged `spring.cloud.vault.enabled: false` into existing `spring.cloud:` block

**Rule going forward:**
Whenever adding spring-cloud-starter-vault-config to a service, immediately add `spring.cloud.vault.enabled: false` to the default `application.yml`.

---

## [2026-03-05] AbstractIntegrationTest package-private — subpackage test cannot access it

**Stage:** 11 (notification-service: SQS Listener + Email)

**Symptom:**
`NotificationListenerIT` (in package `com.seatlock.notification.handler`) failed to compile:
```
AbstractIntegrationTest is not public in com.seatlock.notification; cannot be accessed from outside package
cannot find symbol: variable MAILPIT
cannot find symbol: variable ELASTICMQ
```

**Root cause:**
`AbstractIntegrationTest` was declared without an access modifier (`abstract class AbstractIntegrationTest`), making it package-private to `com.seatlock.notification`. Java prohibits subclasses in different packages from extending package-private classes.

**Fix:**
Add `public` to the class declaration:
```java
public abstract class AbstractIntegrationTest { ... }
```

**Files changed:**
- `notification-service/src/integrationTest/java/com/seatlock/notification/AbstractIntegrationTest.java`

**Pattern:** Always declare `AbstractIntegrationTest` base classes `public` when IT subclasses may live in subpackages.

---

## [2026-03-05] Duplicate `spring:` root key in YAML — SnakeYAML last-key-wins silently drops config

**Stage:** 11 (notification-service: SQS Listener + Email) — also affected Stage 7 booking-service edits

**Symptom:**
After adding `spring.cloud.aws.*` config to `application.yml` and `application-test.yml`, `booking-service` failed to start because `spring.datasource`, `spring.jpa`, `spring.flyway`, `spring.data.redis`, and `spring.jackson` properties were missing.

**Root cause:**
The Edit tool prepended a new `spring:` YAML block at the top of the file instead of merging into the existing `spring:` block. SnakeYAML's behavior when duplicate root keys exist: **the last key wins** — the second `spring:` block overwrites the first entirely. All database/cache/JSON config was silently discarded.

**Fix:**
Rewrite both YAML files completely with a single unified `spring:` root key that contains all nested properties.

**Files changed:**
- `booking-service/src/main/resources/application.yml` (full rewrite)
- `booking-service/src/test/resources/application-test.yml` (full rewrite)

**Rule going forward:**
When adding config to any `application.yml`, always read the full file first and ensure there is exactly one instance of each root key. Never prepend a new top-level block that already exists in the file.

---

## [2026-03-04] HoldControllerIT fails with DataIntegrityViolationException after Stage 10

**Stage:** 10 (booking-service: Cancellation + History)

**Symptom:**
All 6 tests in `HoldControllerIT` fail at `setUp()` with:
`org.springframework.dao.DataIntegrityViolationException: ... PSQLException: ERROR: update or delete on table "holds" violates foreign key constraint "bookings_hold_id_fkey" on table "bookings"`

**Root cause:**
`HoldControllerIT.setUp()` deleted from `holds` before `bookings`. Previously this worked because no prior IT class left bookings in the Testcontainers DB. Stage 10 adds `CancellationControllerIT`, which creates and confirms bookings — those rows remain in the shared DB when `HoldControllerIT` runs next. The delete order `holds → slots → users` violates the FK `bookings.hold_id → holds.hold_id`.

**Fix:**
Prepend `jdbcTemplate.execute("DELETE FROM bookings")` in `HoldControllerIT.setUp()` so the delete order becomes FK-safe: `bookings → holds → slots → users`.

**Files changed:**
- `booking-service/src/integrationTest/java/com/seatlock/booking/controller/HoldControllerIT.java` — added `DELETE FROM bookings` as first delete statement in `setUp()`

**Rule going forward:**
Every IT class `setUp()` that truncates tables must use FK-safe order: child tables before parent tables. For booking-service: `bookings → holds → slots → users`.

---

## [2026-03-03] @MockBean deprecated — compilation warning + wrong annotation

**Stage:** 7 (booking-service: Hold Creation)

**Symptom:**
`HoldControllerIT` compiled with warning: "`MockBean` in `org.springframework.boot.test.mock.mockito` has been deprecated and marked for removal."

**Root cause:**
Spring Boot 3.5.0 deprecated `@MockBean` (and `@SpyBean`) in favour of the new `@MockitoBean` / `@MockitoSpyBean` from `org.springframework.test.context.bean.override.mockito`.

**Fix:**
Replace `import org.springframework.boot.test.mock.mockito.MockBean` with `import org.springframework.test.context.bean.override.mockito.MockitoBean`, and rename the annotation.

```java
// Before
@MockBean SlotVerificationClient slotVerificationClient;

// After
@MockitoBean SlotVerificationClient slotVerificationClient;
```

**Files changed:**
- `booking-service/src/integrationTest/java/com/seatlock/booking/controller/HoldControllerIT.java`

**Pattern:** Use `@MockitoBean` / `@MockitoSpyBean` for all future Spring Boot 3.5.x integration tests.

---

## [2026-03-03] Mockito InvalidUseOfMatchersException — raw value mixed with argument matcher

**Stage:** 7 (booking-service: Hold Creation)

**Symptom:**
`HoldServiceTest.setnxFailure_secondSlot_deletesFirstKeyAndThrows()` failed with:
```
InvalidUseOfMatchersException: Invalid use of argument matchers!
2 matchers expected, 1 recorded
```

**Root cause:**
`when(redisHoldRepository.setnx(slotId1, any(HoldPayload.class)))` mixes a raw `UUID` value (`slotId1`) with an `any()` matcher. Mockito requires that when matchers are used, **all** arguments must be matchers.

**Fix:**
Wrap the raw UUID in `eq()`:
```java
when(redisHoldRepository.setnx(eq(slotId1), any(HoldPayload.class))).thenReturn(true);
when(redisHoldRepository.setnx(eq(slotId2), any(HoldPayload.class))).thenReturn(false);
```

**Files changed:**
- `booking-service/src/test/java/com/seatlock/booking/service/HoldServiceTest.java`

**Pattern:** Whenever a mocked method takes multiple arguments and you use `any()` for one, wrap all literal arguments in `eq()`.

---

## [2026-03-03] Multi-catch with related exception types — compiler error

**Stage:** 7 (booking-service: Hold Creation)

**Symptom:**
```
error: Alternatives in a multi-catch statement cannot be related by subclassing
} catch (RedisConnectionFailureException | DataAccessException e) {
```

**Root cause:**
`RedisConnectionFailureException` extends `DataAccessException`. Java forbids listing a supertype and its subtype in the same multi-catch clause.

**Fix:**
Catch only the supertype `DataAccessException` — it already covers `RedisConnectionFailureException`:
```java
} catch (DataAccessException e) {
    throw new RedisUnavailableException(e);
}
```

**Files changed:**
- `booking-service/src/main/java/com/seatlock/booking/redis/RedisHoldRepository.java`

---

## [2026-03-01] UnnecessaryStubbingException when @BeforeEach stub is unused by a test that never touches the mocked class

**Stage:** 5 (venue-service: Availability Cache)

**Symptom:**
`SlotCacheServiceTest.buildKey_formatsCorrectly()` failed with `UnnecessaryStubbingException`. The test passed its assertions but Mockito failed the suite after the test completed.

**Root cause:**
`@BeforeEach` set up `when(redis.opsForValue()).thenReturn(valueOps)` for all tests. `buildKey_formatsCorrectly()` only calls `slotCacheService.buildKey(...)`, which never touches the `StringRedisTemplate` at all. Mockito's strict mode detects the stub as unused for that test and throws.

This is distinct from the Stage 4 case (where the same stub was *overridden* by a specific test). Here it is completely *unused* because the test exercises a code path that doesn't call Redis.

**Fix:**
Use `Mockito.lenient().when(...)` for the Redis ops setup in `@BeforeEach`. Strict checking remains in force for all other stubs.

```java
// @BeforeEach
Mockito.lenient().when(redis.opsForValue()).thenReturn(valueOps);
```

**Files changed:**
- `venue-service/src/test/java/com/seatlock/venue/cache/SlotCacheServiceTest.java`

**Pattern:** Any `@BeforeEach` stub that not all tests in the class exercise should be wrapped in `Mockito.lenient().when(...)`. The `lenient()` call is surgical — it doesn't disable strict mode globally.

---

## [2026-03-01] Lenient stubs required when @BeforeEach stub is overridden in a test method

**Stage:** 4 (venue-service: Venue + Slot CRUD)

**Symptom:**
`UnnecessaryStubbingException` on `findByVenueIdAndStartTimeBetween` stub set in `@BeforeEach`. Test passes but Mockito's strict mode fails the suite after the test completes.

**Root cause:**
`skipsExistingSlots` test re-stubs the same method to return a non-empty list. Mockito strict mode sees the `@BeforeEach` stub as unused for that particular test (it was overridden, not invoked) and throws `UnnecessaryStubbingException`.

**Fix:**
Use `lenient().when(...)` for stubs in `@BeforeEach` that are intentionally overridden in specific test methods:

```java
lenient().when(slotRepository.findByVenueIdAndStartTimeBetween(eq(venueId), any(), any()))
        .thenReturn(Collections.emptyList());
lenient().when(slotRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
```

**Files changed:**
- `venue-service/src/test/java/com/seatlock/venue/service/SlotGenerationServiceTest.java`

---

## [2026-03-01] JwtUtils @Bean in SecurityConfig causes circular dependency

**Stage:** 4 (venue-service: Venue + Slot CRUD)

**Symptom:**
```
BeanCurrentlyInCreationException: Error creating bean with name 'jwtAuthenticationFilter':
Requested bean is currently in creation: Is there an unresolvable circular reference?
```
Application context fails to load; all integration tests fail with `IllegalStateException`.

**Root cause:**
`SecurityConfig` depends on `JwtAuthenticationFilter` (constructor injection). `JwtAuthenticationFilter` depends on `JwtUtils` (constructor injection). `JwtUtils` was defined as a `@Bean` method inside `SecurityConfig`. Spring cannot construct `SecurityConfig` because it needs `JwtAuthenticationFilter`, which needs `JwtUtils`, which requires `SecurityConfig` to be constructed first.

**Fix:**
Extract the `JwtUtils` bean into a separate `@Configuration` class (`JwtConfig`). Now:
- `JwtConfig` → `JwtUtils` (no dependencies on the security classes)
- `JwtAuthenticationFilter` → `JwtUtils` (from `JwtConfig` — no cycle)
- `SecurityConfig` → `JwtAuthenticationFilter` (no cycle)

```java
@Configuration
public class JwtConfig {
    @Bean
    public JwtUtils jwtUtils(@Value("${seatlock.jwt.secret}") String secret) {
        return new JwtUtils(secret);
    }
}
```

**Files changed:**
- `venue-service/src/main/java/com/seatlock/venue/security/JwtConfig.java` (new)
- `venue-service/src/main/java/com/seatlock/venue/security/SecurityConfig.java` (removed `@Bean JwtUtils`)

**Apply this pattern to all future services** that use `JwtUtils` from `common` alongside a `JwtAuthenticationFilter`.

---

## [2026-03-01] @Testcontainers annotation stops containers between test classes

**Stage:** 3 (user-service: Auth)

**Symptom:**
First IT class (`AuthControllerIT`) passes. Second IT class fails with `Connection refused` to the Postgres container.

**Root cause:**
`@Testcontainers` on the base class stops all `@Container`-annotated fields when each test class completes. Spring caches the `ApplicationContext` (keyed by config) and reuses it for the second class — but the container is now stopped on a different port, so `datasource.url` points nowhere.

**Fix:**
Remove `@Testcontainers`/`@Container` from `AbstractIntegrationTest`. Replace with a static initializer block that starts the container once for the JVM lifetime. All test classes share the same running container and the same Spring context URL.

```java
static final PostgreSQLContainer<?> postgres;
static {
    postgres = new PostgreSQLContainer<>("postgres:15");
    postgres.start();
}
```

**Files changed:**
- `user-service/src/integrationTest/java/com/seatlock/user/AbstractIntegrationTest.java`
- *(Apply same pattern to venue-service, booking-service, notification-service when those stages are implemented)*

---

## [2026-03-01] Spring Security 6.x: wrong status codes + /error route swallows real errors

**Stage:** 3 (user-service: Auth)

**Symptom 1:** Unauthenticated requests to protected endpoints return `403` instead of `401`.

**Symptom 2:** Any unhandled exception (e.g., `ObjectOptimisticLockingFailureException`) causes all endpoints to return `401` regardless of authentication state.

**Root cause 1:** Spring Security 6.x defaults to `Http403ForbiddenEntryPoint` for unauthenticated requests. No explicit entry point → 403.

**Root cause 2:** Tomcat forwards unhandled exceptions to `/error`. If `/error` is not in `permitAll`, Spring Security intercepts it and returns 401 (unauthenticated), hiding the real error status entirely.

**Fix:**
```java
.authorizeHttpRequests(auth -> auth
    .requestMatchers("/api/v1/auth/**", "/actuator/health", "/error").permitAll()  // ← /error required
    .anyRequest().authenticated())
.exceptionHandling(ex -> ex
    .authenticationEntryPoint((req, res, e) -> res.sendError(HttpServletResponse.SC_UNAUTHORIZED)))  // ← explicit 401
```

**Files changed:**
- `user-service/src/main/java/com/seatlock/user/security/SecurityConfig.java`

---

## [2026-03-01] Pre-initialized UUID causes Hibernate to UPDATE instead of INSERT

**Stage:** 3 (user-service: Auth)

**Symptom:**
`POST /auth/register` throws `ObjectOptimisticLockingFailureException: Batch update returned unexpected row count from update [0]`.

**Root cause:**
`User.userId` is initialized to `UUID.randomUUID()` in the field declaration so unit tests can call `getUserId()` without persisting. Hibernate sees a non-null `@Id` and assumes the entity already exists → issues `UPDATE` → 0 rows affected → exception.

**Fix:**
Implement `Persistable<UUID>` with an `isNew` flag. Flip it to `false` in `@PostPersist` and `@PostLoad`. Hibernate calls `isNew()` and correctly issues `INSERT` on first save.

```java
public class User implements Persistable<UUID> {
    @Transient private boolean isNew = true;
    @PostPersist @PostLoad void markNotNew() { this.isNew = false; }
    @Override public boolean isNew() { return isNew; }
}
```

**Files changed:**
- `user-service/src/main/java/com/seatlock/user/domain/User.java`

---

## [2026-02-28] Testcontainers fails with HTTP 400 on Docker Desktop 4.60.1

**Stage:** 2 (Testing Infrastructure)

**Symptom:**
```
IllegalStateException: Could not find a valid Docker environment
BadRequestException (Status 400) from both TCP and Npipe strategies
```

**Root cause:**
Docker Desktop 4.60.1 enforces a minimum API version of 1.44. Testcontainers 1.21.0 uses a shaded copy of docker-java that defaults to API version 1.32. Every request goes to `/v1.32/...` → Docker returns 400.

Setting `DOCKER_API_VERSION=1.44` as an env var has no effect — the shaded docker-java does **not** read that env var. It reads the API version from the JVM system property `api.version`.

**Fix:**
Add `jvmArgs("-Dapi.version=1.44")` to every `integrationTest` task in every service's `build.gradle.kts`:

```kotlin
tasks.register<Test>("integrationTest") {
    ...
    jvmArgs("-Dapi.version=1.44")   // ← forces shaded docker-java to use v1.44
    ...
}
```

**Files changed:**
- `user-service/build.gradle.kts`
- `venue-service/build.gradle.kts`
- `booking-service/build.gradle.kts`
- `notification-service/build.gradle.kts`
- `~/.testcontainers.properties` — set to `testcontainers.reuse.enable=true` only (no strategy overrides needed)

---
