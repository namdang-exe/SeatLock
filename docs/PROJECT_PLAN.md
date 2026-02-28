# SeatLock — Project Plan

## Current Status
- Phase: 0 — System Design
- Current Section: Phase 1 — Foundation (NEXT)
- Last Completed: Phase 0 — System Design ✅ COMPLETE (completed 2026-02-27)
- Last Updated: 2026-02-27

---

## Phase 0 — System Design (No Code)
Goal: Produce complete high-level system design with diagrams before 
any implementation begins.

### Part 1 — Design Interview
- [x] Section 1 — Requirements → docs/system-design/01-requirements.md
- [x] Section 2 — Core Entities → docs/system-design/02-core-entities.md
- [x] Section 3 — API / Interface → docs/system-design/03-api-interface.md
- [x] Section 4 — Data Flow → docs/system-design/04-data-flow.md (reviewed and corrected 2026-02-19)
- [x] Section 5 — High-Level Design → docs/system-design/05-high-level-design.md
- [x] Section 6 — Deep Dives → docs/system-design/06-deep-dives.md

### Part 2 — Diagrams
- [x] Diagram 1 — System Context → docs/diagrams/01-system-context.md
- [x] Diagram 2 — Service Architecture → docs/diagrams/02-service-architecture.md
- [x] Diagram 3 — Booking Flow Sequence → docs/diagrams/03-booking-flow-sequence.md
- [x] Diagram 4 — Data Model ERD → docs/diagrams/04-data-model-erd.md
- [x] Diagram 5 — Infrastructure → docs/diagrams/05-infrastructure.md

### Part 2 — Documentation
- [x] system-design/01-requirements.md ✅
- [x] system-design/02-core-entities.md ✅
- [x] system-design/03-api-interface.md ✅
- [x] system-design/04-data-flow.md ✅
- [x] system-design/05-high-level-design.md ✅
- [x] system-design/06-deep-dives.md ✅
- [x] All ADRs written (ADR-001 through ADR-008) → docs/decisions/
- [x] CONTEXT.md finalized
- [x] open-questions.md finalized
- [x] M0-phase0-complete.md written

---

## Phase 1 — Foundation (Milestone 1)
Goal: User auth + venue catalog. No booking logic yet.

- [ ] user-service (JWT auth, registration, login)
- [ ] venue-service (venue CRUD, slot catalog)
- [ ] Docker Compose local setup
- [ ] Flyway migrations for both services
- [ ] Basic CI with GitHub Actions

---

## Phase 2 — Core Booking (Milestone 2)
Goal: The double-booking problem solved end to end.

- [ ] booking-service (Redis TTL holds + Postgres transaction + locking)
  NOTE: hold-service merged into booking-service per Section 2 decision
- [ ] availability-service (Redis cache + invalidation)
- [ ] Integration tests for concurrency scenarios

---

## Phase 3 — Resilience (Milestone 3)
Goal: System handles failure gracefully.

- [ ] Fallback when Redis is down
- [ ] Idempotency keys across all write endpoints
- [ ] Circuit breakers between services
- [ ] Retry strategies

---

## Phase 4 — Observability + Security (Milestone 4)
Goal: Production-grade visibility and secret management.

- [ ] Prometheus metrics on all services
- [ ] Grafana dashboards
- [ ] Vault integration for secrets
- [ ] Health endpoints + blackbox monitoring

---

## Phase 5 — Infrastructure + Frontend (Milestone 5)
Goal: Deployed to AWS, usable in browser.

- [ ] Terraform for ECS Fargate + RDS + ElastiCache
- [ ] GitHub Actions full deploy pipeline
- [ ] React frontend (browse slots, hold, confirm)

---

## Open Questions
See docs/open-questions.md (all resolved as of 2026-02-27)

## Key Decisions Made
See docs/decisions/ for all ADRs

## Implementation Plan
See docs/CODING_PLAN.md for stage-by-stage implementation plan

## Project Navigation
See docs/INDEX.md for a complete navigation index of all project files
```

---

### The prompt to continue from a new terminal session
```
Read the following files before doing anything else, in this order:
1. docs/PROJECT_PLAN.md — understand where we are and what's next
2. docs/CONTEXT.md — understand all decisions made so far
3. The last completed file listed in PROJECT_PLAN.md

We are building SeatLock. Check PROJECT_PLAN.md for the current status.

Once you have read those files, tell me:
- What has been completed
- What section or milestone we are on
- A one-paragraph summary of the key decisions made so far

Then we will continue from where we left off. Do not start working yet — 
confirm your understanding first and wait for me to say "continue."