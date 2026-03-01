# SeatLock — Bug Log

Brief record of significant bugs and their fixes. Add new entries at the top.

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
