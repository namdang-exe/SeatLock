# SeatLock

Distributed Reservation Platform with Real-Time Availability and Concurrency-Safe Booking.

---

## How to Work on This Project with Claude Code

This project uses Claude Code as the primary development tool.
Every session follows a strict start and end ritual to maintain context across sessions since Claude has no memory between terminals.

---

## Starting a New Session

Open a new terminal, navigate to the project root, type `claude` and paste this prompt:
```
I am sharing four files with you. Read all four before doing anything else:
  - CONTEXT.md        — all architecture decisions, key numbers, confirmed services
  - INDEX.md          — navigation map for the entire project including stage summary
  - CODING_PLAN.md    — full implementation plan (do NOT read in full — only read
                        the section for the current stage, identified below)
  - open-questions.md — all design questions (resolved, reference only)

  A fifth file, docs/BUGS.md, is available if you need to look up a past bug fix
  or environment workaround — read it on demand, not upfront.

  We are building SeatLock — a distributed reservation platform with real-time
  availability and concurrency-safe booking.

  Phase 0 (system design) is complete. You are now implementing the project.

  Once you have read the files above:
  1. Check the stage summary table in INDEX.md to find the first stage
     that is NOT STARTED or IN PROGRESS
  2. Read only that stage's section in CODING_PLAN.md
  3. Then tell me:
     - Which stage we are on
     - What it builds and what the acceptance criteria are
     - Which ADRs or deep dive decisions apply
     - Any questions before we begin

  Do not start writing any code yet. Wait for me to say "let's continue."
```

---

## Ending Every Session

Before closing any terminal, paste this prompt:
```
Before we close this session, do the following in order. 
Do not skip any step.

1. UPDATE docs/CODING_PLAN.md
   - Mark the current stage as COMPLETE or IN PROGRESS
   - Note exactly where we stopped if IN PROGRESS
   - If COMPLETE, confirm all acceptance criteria were met

2. UPDATE docs/CONTEXT.md
   - Add any new decisions made this session as "We chose X over Y because Z"
   - Update current phase and stage status
   - Add any new constraints or rules that emerged during implementation

3. UPDATE docs/INDEX.md
   - Update the stage status table to reflect current progress
   - Add any new files we created to the relevant sections
   - Add any new domain error codes, Redis keys, or API endpoints 
     that were implemented this session

4. UPDATE docs/open-questions.md
   - Mark any questions we resolved this session as resolved
   - Add any new questions that came up during implementation

5. UPDATE docs/BUGS.md
   - If we hit and resolved a significant bug this session, add an entry at the top
   - Include: symptom, root cause, fix, and files changed
   - Skip this step if no notable bugs were encountered

6. CREATE a session summary
   Write a short entry at the top of docs/milestones/session-log.md:
   
   ## Session [date] — Stage N: [stage name]
   - What we completed
   - What was left incomplete and exactly where we stopped
   - Any decisions made that deviate from the original design
   - Any gotchas or surprises discovered during implementation
   - What the next session should do first

7. CONFIRM when done
   Tell me:
   - Every file that was modified
   - The exact status of the current stage
   - The first thing the next session should do when it starts

Do not close until all seven steps are complete.
```

---

## Running Locally

### Prerequisites
- **Java 21** — `JAVA_HOME` must point to a JDK 21 installation (e.g. Eclipse Temurin 21)
- **Docker Desktop** — must be running before starting infrastructure

### 1 — Start infrastructure (once per machine restart)

```bash
docker compose up -d
```

Starts Postgres 15, Redis 7, Mailhog (email), and ElasticMQ (SQS) in the background.
Verify with `docker compose ps` — all services should show `Up`.
Close all docker with `docker compose down -v` when finished to free up resources.

### 2 — Start services (one terminal per service)

All commands must be run from the project root (`D:/projects/SeatLock`).

```bash
# Terminal 1
cd D:/projects/SeatLock && gradlew :user-service:bootRun          # http://localhost:8081

# Terminal 2
cd D:/projects/SeatLock && gradlew :venue-service:bootRun         # http://localhost:8082

# Terminal 3
cd D:/projects/SeatLock && gradlew :booking-service:bootRun       # http://localhost:8083

# Terminal 4
cd D:/projects/SeatLock && gradlew :notification-service:bootRun  # http://localhost:8084
```

### 3 — Start the frontend (one terminal)

```bash
# Terminal 5
cd D:/projects/SeatLock/seatlock-ui
npm install      # first time only
npm run dev      # http://localhost:5173
```

The Vite dev server proxies all API calls to the correct backend service — no CORS config needed.

### 4 — Verify everything is up

```bash
# {"status":"UP"}
curl http://localhost:8081/actuator/health
curl http://localhost:8082/actuator/health
curl http://localhost:8083/actuator/health
curl http://localhost:8084/actuator/health
```

### Useful URLs

| Service | URL |
|---------|-----|
| **Frontend** | http://localhost:5173 |
| Mailpit (captured emails) | http://localhost:8025 |
| ElasticMQ (SQS web UI) | http://localhost:9325 |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3001 |

### Stop everything

```bash
docker compose down          # stop containers, keep Postgres data
docker compose down -v       # stop containers + wipe all data
```

---

## Project Structure
```
seatlock/
├── README.md                  ← you are here
├── build.gradle.kts           ← root Gradle build (shared versions + plugins)
├── settings.gradle.kts        ← declares all subprojects
├── gradle.properties          ← JVM args, Gradle daemon config
├── docker-compose.yml         ← local dev: Postgres, Redis, Mailhog, ElasticMQ
├── .github/
│   └── workflows/
│       └── ci.yml             ← build + test on every PR
├── common/                    ← shared library (DTOs, exceptions, JwtUtils)
├── user-service/              ← registration, login, JWT issuance  (port 8081)
├── venue-service/             ← venues, slots, availability cache   (port 8082)
├── booking-service/           ← holds, bookings, expiry job         (port 8083)
├── notification-service/      ← SQS consumer, email/SMS dispatch    (port 8084)
├── seatlock-ui/               ← React 18 frontend (Vite dev server)  (port 5173)
├── infra/
│   ├── postgres/init.sql      ← creates user_db, venue_db, booking_db
│   └── terraform/             ← AWS infrastructure (Stage 16)
└── docs/
    ├── CONTEXT.md             ← Claude reads this every session
    ├── CODING_PLAN.md         ← 16-stage implementation plan
    ├── PROJECT_PLAN.md        ← phase/milestone tracker
    ├── INDEX.md               ← navigation map for the entire project
    ├── open-questions.md      ← all design questions and resolutions
    ├── system-design/         ← phase 0 design documents
    ├── diagrams/              ← Mermaid diagrams
    ├── decisions/             ← ADRs (001–008)
    └── milestones/            ← session log + milestone sign-offs
```

---

## API Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/api/v1/auth/register` | None | Create a new user account |
| `POST` | `/api/v1/auth/login` | None | Authenticate and receive a JWT |
| `GET` | `/api/v1/venues` | JWT | Browse all active venues |
| `GET` | `/api/v1/venues/{venueId}/slots` | JWT | Browse slots for a venue (`?date=`, `?status=`) |
| `POST` | `/api/v1/holds` | JWT | Place a hold on one or more slots (all-or-nothing) |
| `POST` | `/api/v1/bookings` | JWT | Confirm all holds in a session (all-or-nothing) |
| `GET` | `/api/v1/bookings` | JWT | Retrieve all bookings for the authenticated user |
| `POST` | `/api/v1/bookings/{confirmationNumber}/cancel` | JWT | Cancel all bookings in a session (all-or-nothing) |
| `GET` | `/api/v1/admin/venues/{venueId}/bookings` | JWT (admin) | List all confirmed bookings for a venue |

Full request/response shapes: `docs/system-design/03-api-interface.md`

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language / Runtime | Java 21, Spring Boot 3.x |
| Security | Spring Security, JWT (user auth + inter-service) |
| Persistence | Spring Data JPA, PostgreSQL 15 (RDS) |
| Cache / Holds | Spring Data Redis, Redis 7 (ElastiCache) |
| Migrations | Flyway |
| Messaging | AWS SQS (ElasticMQ locally) |
| Secrets | HashiCorp Vault (Spring Cloud Vault) |
| Service discovery | AWS Cloud Map |
| Resilience | Resilience4j (circuit breaker + retry) |
| Observability | Spring Actuator, Micrometer, Prometheus, Grafana |
| Build | Gradle 8.x (Kotlin DSL), multi-module |
| Testing | JUnit 5, Testcontainers, Mockito, MockMvc |
| Frontend | React 18, TypeScript, Vite, TanStack Query v5, Tailwind CSS |
| Infrastructure | Terraform, AWS ECS Fargate, Docker |
| CI/CD | GitHub Actions |

---

## Phases & Milestones

See docs/PROJECT_PLAN.md for full breakdown and current status.

---

## Key Design Decisions

See docs/decisions/ for all ADRs.
See docs/CONTEXT.md for a quick summary of every decision made.

## Observability

All services expose Prometheus metrics at `/actuator/prometheus`.
Prometheus scrapes all four services automatically — view targets at http://localhost:9090/targets.
Grafana dashboard (SeatLock Overview) is pre-provisioned at http://localhost:3001 (no login required).