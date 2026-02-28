# Diagram 02 — Service Architecture

> Phase 0 — Design Only
> Reflects all decisions made through Section 5 (High-Level Design).
> See `docs/system-design/05-high-level-design.md` for full decision rationale.

---

## Service Architecture Diagram

```mermaid
graph TD
    Browser["React Frontend\n(React 18 + TypeScript)"]

    subgraph VPC["AWS VPC"]

        ALB["ALB\npath-based routing"]

        subgraph ECS["ECS Fargate"]
            US["user-service\nregistration · login\nJWT issuance"]
            VS["venue-service\nvenue + slot CRUD\navailability reads"]
            BS["booking-service\nholds · bookings\nexpiry job · cache DEL"]
            NS["notification-service\nemail · in-app · SMS\n(SQS consumer)"]
        end

        subgraph Stores["Data Stores"]
            PG[("PostgreSQL\nshared cluster\n─────────────\nusers\nvenues · slots\nholds · bookings")]
            Cache[("Redis / ElastiCache\n─────────────\nhold:{slotId}\nTTL = 30 min\n─────────────\nslots:{venueId}:{date}\nTTL = 5s")]
            SQS[["SQS Queue\nHoldExpiredEvent\nBookingConfirmedEvent\nBookingCancelledEvent"]]
        end

        Vault["HashiCorp Vault\nsecrets · service JWT key"]
        CloudMap["AWS Cloud Map\ninternal DNS\nvenue-service.seatlock.local"]

    end

    Ext["Email / SMS Providers"]

    %% External traffic
    Browser -->|"HTTPS"| ALB

    %% ALB path routing
    ALB -->|"/api/v1/auth/**"| US
    ALB -->|"/api/v1/venues/**\n/api/v1/admin/**"| VS
    ALB -->|"/api/v1/holds/**\n/api/v1/bookings/**"| BS

    %% Inter-service: sync
    BS -->|"Service JWT\nslot verification\nGET /internal/slots"| VS
    CloudMap -. "resolves DNS" .-> BS

    %% Inter-service: async
    BS -->|"publish events"| SQS
    SQS -->|"consume"| NS
    NS --> Ext

    %% Database access
    US --- PG
    VS --- PG
    BS --- PG

    %% Redis access
    VS -->|"cache read/write\nTTL 5s"| Cache
    BS -->|"SETNX · DEL"| Cache

    %% Secrets
    US -. "secrets" .-> Vault
    VS -. "secrets" .-> Vault
    BS -. "secrets + service JWT key" .-> Vault
    NS -. "secrets" .-> Vault
```

---

## Communication Key

| Arrow style | Meaning |
|-------------|---------|
| Solid `-->` | Synchronous HTTP or direct I/O |
| Dotted `-.->` | Configuration / secret fetch (startup or periodic) |

| Label | Detail |
|-------|--------|
| Service JWT | booking-service signs `sub: booking-service` JWT with Vault-sourced secret; venue-service validates |
| SETNX · DEL | `SET hold:{slotId} NX EX 1800` (hold creation); `DEL` on confirm/cancel/hold |
| cache read/write | `GET slots:{venueId}:{date}` on browse; `SET ... EX 5` on miss; `DEL` triggered by booking-service |
| publish events | booking-service publishes after hold expiry, booking confirmation, booking cancellation |

---

## What Changed From Early Sketches

| Sketch assumption | Final decision |
|-------------------|---------------|
| API Gateway / Auth layer at the edge | Single ALB with path-based rules — no API Gateway needed at 10k DAU |
| Separate Reservation Service | Split into booking-service (holds + bookings) and venue-service (venues + slots + availability) |
| No cache layer shown | Redis/ElastiCache: holds (30min TTL) + availability cache (5s TTL) |
| No async messaging shown | SQS queue between booking-service and notification-service |
| No secrets management shown | HashiCorp Vault for all services; service JWT key for inter-service auth |
| No service discovery shown | AWS Cloud Map: ECS tasks auto-register; stable internal DNS |
