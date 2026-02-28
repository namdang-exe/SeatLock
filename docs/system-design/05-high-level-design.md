# 05 — High-Level Design

## Context

This document captures the high-level architecture decisions for SeatLock, agreed in the Section 5 design interview. It defines service boundaries, communication patterns, service discovery, external traffic routing, and inter-service authentication.

Earlier sections define requirements (01), entities (02), API shapes (03), and data flows (04). This document defines how the services fit together as a system.

---

## Confirmed Service Boundaries

| Service | Responsibilities | DB Tables |
|---------|-----------------|-----------|
| **user-service** | Registration, login, JWT issuance, user profile | `users` |
| **venue-service** | Venue CRUD, slot CRUD + auto-generation (admin), slot reads (users), availability reads — Redis cache + Postgres fallback | `venues`, `slots` (metadata) |
| **booking-service** | Hold creation, booking confirmation, cancellation, booking history, hold expiry job, Redis cache invalidation (DEL), `slot.status` writes | `holds`, `bookings`; `slots.status` column (Phase 0 shared cluster — see D-5.4) |
| **notification-service** | Email + in-app + SMS dispatch | None (stateless SQS consumer) |

### Merged Services (Prior Decisions)

| Removed service | Merged into | Reason |
|-----------------|-------------|--------|
| ~~availability-service~~ | venue-service | Availability is a read view over slot data that venue-service already owns. A separate service requires either shared DB (violates isolation) or a sync HTTP hop on the hot read path. |
| ~~hold-service~~ | booking-service | Hold and Booking are the same domain. A separate hold-service adds a sync cross-service call on the critical confirmation path. |

---

## Communication Patterns

### 1. booking-service → notification-service: Async (SQS)

booking-service publishes an event to SQS after each lifecycle transition that triggers a notification:

| Event | Published after |
|-------|----------------|
| `HoldExpiredEvent` | Expiry job marks hold as `EXPIRED` |
| `BookingConfirmedEvent` | `POST /bookings` transaction commits |
| `BookingCancelledEvent` | `POST /bookings/{confirmationNumber}/cancel` transaction commits |

notification-service is a stateless SQS consumer. It dispatches email + in-app + SMS on receipt.

**Why async:**
- The user's HTTP response is already returned before notification-service does any work. The booking is committed; notification delivery is a downstream concern.
- If notification-service is unavailable, the booking operation is unaffected. Events remain in the queue and are processed when the service recovers.
- Notifications are eventually consistent by design — a short delivery lag is acceptable.

**Alternative rejected:**
- Sync HTTP: notification-service availability would become a hard dependency of the booking write path. A degraded notification-service would fail or slow booking confirmations.

---

### 2. booking-service → venue-service: Sync HTTP (verification) + Shared Postgres (slot.status writes)

There are two distinct interactions, handled differently:

#### Slot existence verification — sync HTTP

At hold creation, booking-service calls venue-service to verify that all requested slot IDs exist and to retrieve their `venueId` (needed for cache key construction):

```
GET /api/v1/internal/slots?ids={slotId1},{slotId2,...}
Authorization: Bearer <service-jwt>
```

This is a sync call because booking-service cannot proceed without knowing the slot exists. If venue-service is unavailable, hold creation fails with 503.

#### slot.status writes — shared Postgres cluster (Phase 0 pragmatic compromise)

booking-service writes `slot.status` directly as part of its atomic Postgres transactions. Example from hold creation:

```sql
BEGIN;
  INSERT INTO holds ...;
  UPDATE slots SET status = 'HELD'
    WHERE slot_id IN (:slotIds) AND status = 'AVAILABLE';
COMMIT;
```

This atomicity is non-negotiable: the `AND status = 'AVAILABLE'` guard must execute in the same transaction as the hold INSERT. Making slot.status updates async (Option B — event-driven) would decouple this guard from the write it protects, eliminating the secondary safety gate for the ≤60s expiry lag window.

**Phase 0 compromise:** booking-service and venue-service share the same Postgres cluster. Service ownership is enforced at the application layer — booking-service writes `slots.status` only; venue-service owns all other slot fields. True DB isolation (extracting `slot_availability` into booking-service's schema) is a Phase 1+ architectural goal.

**Cache invalidation** is a direct Redis `DEL` by booking-service — not a service call to venue-service.

**Alternative rejected:**
- Async events for slot.status: breaks the atomicity of the hold creation transaction. The secondary Postgres guard (`AND status = 'AVAILABLE'`) cannot be enforced if slot.status is updated asynchronously by venue-service consuming an event.

---

### 3. user-service → booking-service: No Runtime Call

booking-service validates JWTs **locally** using Spring Security. It extracts `userId` and `role` from the token claims at request time — no HTTP call to user-service is required.

The `@Scheduled` expiry job in booking-service has no user context and makes no call to user-service.

**Account lifecycle events** (user deletion, suspension) would be handled via async event from user-service to booking-service (Phase 1+). Not in scope for Phase 0.

---

## Service Discovery: AWS Cloud Map

**Decision:** AWS Cloud Map for all internal service-to-service communication.

ECS Fargate has native Cloud Map integration. Each service's tasks auto-register in Cloud Map on startup and deregister on termination — no manual DNS management required. booking-service calls venue-service at a stable internal DNS name:

```
http://venue-service.seatlock.local:8080
```

All inter-service calls stay within the VPC. Cloud Map resolves to the IP of a healthy ECS task for the target service.

**Alternatives rejected:**

| Option | Reason rejected |
|--------|----------------|
| Internal ALB per service | Adds one ALB per service (cost + extra routing hop); unnecessary at 4 services and 10k DAU |
| Hardcoded environment variables | Brittle — ECS Fargate tasks get ephemeral IPs; breaks when tasks restart; Cloud Map handles this automatically |

---

## External Traffic Routing: Single ALB, Path-Based Rules

**Decision:** One public-facing Application Load Balancer with path-based listener rules routing to ECS target groups.

| Path prefix | Target group | Service |
|-------------|-------------|---------|
| `/api/v1/auth/**` | user-service-tg | user-service |
| `/api/v1/venues/**` | venue-service-tg | venue-service |
| `/api/v1/holds/**` | booking-service-tg | booking-service |
| `/api/v1/bookings/**` | booking-service-tg | booking-service |
| `/api/v1/admin/**` | venue-service-tg | venue-service |
| (no public endpoint) | — | notification-service |

notification-service has no public-facing endpoints — it consumes from SQS only.

Rate limiting on `POST /holds` (OQ-14) is enforced at the ALB level via AWS WAF, or via Spring's rate-limiting interceptor — no API Gateway required.

**Alternatives rejected:**

| Option | Reason rejected |
|--------|----------------|
| One ALB per service | React frontend would need four different base URLs; adds cost and complexity for no benefit at this scale |
| API Gateway in front of ALB | Adds cost, an extra managed hop, and cold-start concerns; API Gateway features (request transformation, usage plans) are not needed in Phase 0; ALB alone handles 10k DAU |

---

## Inter-Service Auth: Service JWT

**Decision:** Service JWT for booking-service → venue-service calls.

booking-service signs a short-lived JWT with a service identity claim using a shared secret sourced from Vault:

```json
{
  "sub": "booking-service",
  "iss": "seatlock-internal",
  "exp": <now + 5 minutes>
}
```

This token is placed in the `Authorization: Bearer` header of every internal call to venue-service. venue-service validates the JWT signature (shared secret from Vault) and the `sub` claim before processing the request.

Both services retrieve the shared signing secret from Vault on startup.

**Why Service JWT:**
- Works for all callers including the expiry job — which has no user context and cannot forward a user JWT.
- Provides defense-in-depth beyond network-level security groups — an unauthorized process inside the VPC cannot call venue-service without the service credential.
- Simpler than SigV4 — no AWS SDK signing complexity in Spring Boot; JWT validation is already a Spring Security primitive in the codebase.
- Clean service identity semantics — `sub: booking-service` makes the caller identity explicit in logs and audit trails.

**Alternatives rejected:**

| Option | Reason rejected |
|--------|----------------|
| Forward user JWT | Expiry job has no user context; ties internal trust to user session lifecycle, which is semantically incorrect |
| IAM task roles + SigV4 | Correct AWS-native approach but adds SDK signing complexity for internal HTTP calls; better suited for AWS-managed services (S3, DynamoDB) |
| Network-level trust only | Security groups provide perimeter control but no application-level identity; a compromised process in the same security group could call internal APIs without restriction |

---

## System Topology

```
┌─────────────────────────────────────────────────────────────────────┐
│                         AWS VPC                                     │
│                                                                     │
│  ┌──────────────┐                                                   │
│  │ React / Browser│                                                 │
│  └──────┬───────┘                                                   │
│         │ HTTPS                                                     │
│  ┌──────▼──────────────────────────────────────┐                   │
│  │          ALB (path-based routing)            │                   │
│  └──────┬──────────────┬────────────────┬───────┘                  │
│         │              │                │                           │
│  ┌──────▼──────┐ ┌─────▼──────┐ ┌──────▼───────┐                  │
│  │ user-service│ │venue-service│ │booking-service│                  │
│  │  (ECS)      │ │  (ECS)      │ │  (ECS)        │                 │
│  └──────┬──────┘ └─────┬──────┘ └──────┬────────┘                 │
│         │              │   ▲ Cloud Map  │ Service JWT               │
│         │              │   └───────────┘                           │
│         │              │                │                           │
│  ┌──────▼──────────────▼────────────────▼───────┐                  │
│  │         Postgres (shared cluster)             │                  │
│  │  users | venues | slots | holds | bookings    │                  │
│  └───────────────────────────────────────────────┘                  │
│                                                                     │
│  ┌──────────────────────────────┐                                   │
│  │   Redis / ElastiCache        │◄── venue-service (cache R/W)      │
│  │   hold:{slotId}              │◄── booking-service (SETNX, DEL)   │
│  │   slots:{venueId}:{date}     │                                   │
│  └──────────────────────────────┘                                   │
│                                                                     │
│  booking-service ──► SQS Queue ──► notification-service            │
│                                        │                            │
│                               ┌────────▼────────────┐              │
│                               │ Email / SMS / In-App │              │
│                               └─────────────────────┘              │
│                                                                     │
│  All services ──► HashiCorp Vault (secrets, service JWT key)       │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Key Decisions

| ID | Decision | Rationale |
|----|----------|-----------|
| D-5.1 | `slot.status` stays on Slot entity (denormalized — Model A) | Availability is the hot read path (every 5s poll); booking-service owns all three write paths; denormalization risk is fully mitigated |
| D-5.2 | availability-service merged into venue-service | Availability is a read view over slot data venue-service already owns; separate service requires shared DB or sync HTTP hop on hot read path |
| D-5.3 | booking-service → notification-service: async (SQS) | User response does not depend on notification delivery; notification-service unavailability must not affect booking writes |
| D-5.4 | booking-service → venue-service: sync HTTP (slot verification) + shared Postgres (slot.status writes, Phase 0) | slot.status UPDATE must be atomic with holds INSERT; async events would break the secondary Postgres safety gate; shared cluster is a documented Phase 0 compromise |
| D-5.5 | user-service → booking-service: no runtime call | JWT self-validates locally via Spring Security; no cross-service call on the request path |
| D-5.6 | Service discovery: AWS Cloud Map | Native ECS Fargate integration; stable internal DNS; tasks auto-register/deregister; no extra ALB per service |
| D-5.7 | External routing: single ALB, path-based rules | One DNS entry for frontend; clean path routing to target groups; no API Gateway needed at 10k DAU |
| D-5.8 | Inter-service auth: Service JWT | Covers non-user callers (expiry job); defense-in-depth beyond security groups; simpler than SigV4; reuses existing JWT infrastructure from the stack |

---

## Open Questions Resolved in This Section

| # | Resolution |
|---|------------|
| OQ-15 | availability-service merged into venue-service |
| OQ-16 | booking→notification: async (SQS); booking→venue: sync HTTP + shared Postgres; user→booking: no runtime call |
| OQ-17 | Service discovery: AWS Cloud Map — ECS native, stable internal DNS, auto-registration |
| Q4 | External routing: single ALB, path-based listener rules, four target groups |
| Q5 | Inter-service auth: Service JWT signed with Vault-sourced shared secret |
