# SeatLock — Session Log

> Most recent session at the top.
> Each entry records what was done, decisions made, and where to continue.

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
