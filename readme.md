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
I am sharing three files with you. Read all three before doing anything else:
- CONTEXT.md        — all architecture decisions, key numbers, confirmed services
- INDEX.md          — navigation map for the entire project including stage summary
- open-questions.md — all design questions (resolved, reference only)

We are building SeatLock — a distributed reservation platform with real-time
availability and concurrency-safe booking.

Phase 0 (system design) is complete. You are now implementing the project.

Once you have read the three files above:
1. Check the stage summary table in INDEX.md to find the first stage 
   that is NOT STARTED or IN PROGRESS
2. Read that stage's full detail in CODING_PLAN.md before we begin
3. Then tell me:
   - Which stage we are on
   - What it builds and what the acceptance criteria are
   - Which ADRs or deep dive decisions apply
   - Any questions before we begin

Do not read CODING_PLAN.md in full — only read the current stage section.
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

5. CREATE a session summary
   Write a short entry at the top of docs/milestones/session-log.md:
   
   ## Session [date] — Stage N: [stage name]
   - What we completed
   - What was left incomplete and exactly where we stopped
   - Any decisions made that deviate from the original design
   - Any gotchas or surprises discovered during implementation
   - What the next session should do first

6. CONFIRM when done
   Tell me:
   - Every file that was modified
   - The exact status of the current stage
   - The first thing the next session should do when it starts

Do not close until all six steps are complete.
```

---

## Project Structure
```
seatlock/
├── README.md                  ← you are here
├── docs/
│   ├── CONTEXT.md             ← Claude reads this every session
│   ├── PROJECT_PLAN.md        ← tracks progress across all phases
│   ├── open-questions.md      ← unresolved design questions
│   ├── system-design/         ← phase 0 design documents
│   ├── diagrams/              ← mermaid diagrams
│   ├── decisions/             ← ADRs
│   └── milestones/            ← milestone completion summaries
└── services/                  ← microservices (phase 1+)
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

[paste your tech stack here]

---

## Phases & Milestones

See docs/PROJECT_PLAN.md for full breakdown and current status.

---

## Key Design Decisions

See docs/decisions/ for all ADRs.
See docs/CONTEXT.md for a quick summary of every decision made.