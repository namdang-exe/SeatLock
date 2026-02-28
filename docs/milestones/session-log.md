# SeatLock — Session Log

> Most recent session at the top.
> Each entry records what was done, decisions made, and where to continue.

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
