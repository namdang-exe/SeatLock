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
Before we do anything, read these files in order:
1. docs/PROJECT_PLAN.md
2. docs/CONTEXT.md
3. docs/open-questions.md
4. Any files listed as completed in PROJECT_PLAN.md

When done, tell me:
- What phase and section we are on
- A bullet summary of every decision made so far
- What open questions are unresolved
- What we are working on today

Do not start any work until I confirm. Wait for me to say "let's continue."
```

---

## Ending Every Session

Before closing any terminal, paste this prompt:
```
Before I close this session, do the following in order:

1. Update docs/CONTEXT.md
   - Set the current phase, section, and milestone status
   - Add a one-line "We decided X because Y" summary for every decision made this session
   - Update all file pointers to include anything new we created
   - Flag anything unresolved

2. Update docs/PROJECT_PLAN.md
   - Check off everything completed this session
   - Update the Current Status block at the top with today's date

3. Update docs/open-questions.md
   - Add any unresolved questions from this session
   - Mark any previously open questions we resolved today as done

4. Confirm what files were created or modified this session and give me a one-paragraph summary of what we accomplished.

Be specific in every file. Future Claude sessions will read these files cold with no memory of this conversation.
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