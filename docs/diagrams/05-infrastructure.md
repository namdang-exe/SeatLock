# Diagram 05 — Infrastructure

## Overview

AWS infrastructure topology for SeatLock. All application workloads run inside a single VPC. Public-facing traffic enters through a single ALB. Services communicate internally via AWS Cloud Map DNS.

---

## Infrastructure Diagram

```mermaid
flowchart TB
    Dev["Developer\nGitHub Push"]
    GHA["GitHub Actions\nCI/CD Pipeline\n───────────────\nBuild · Test\nPush to ECR\nDeploy to ECS"]
    ECR["Amazon ECR\nContainer Registry\n───────────────\nuser-service image\nvenue-service image\nbooking-service image\nnotification-service image"]

    Dev -- "git push" --> GHA
    GHA -- "docker push" --> ECR
    GHA -- "ecs update-service" --> ECS_Cluster

    subgraph Internet
        Browser["React Frontend\n(Browser / CDN)"]
    end

    subgraph VPC["AWS VPC"]
        subgraph PublicSubnet["Public Subnets (Multi-AZ)"]
            ALB["Application Load Balancer\n───────────────\nPath-based listener rules\n/api/v1/auth/**     → user-service-tg\n/api/v1/venues/**   → venue-service-tg\n/api/v1/admin/**    → venue-service-tg\n/api/v1/holds/**    → booking-service-tg\n/api/v1/bookings/** → booking-service-tg"]
        end

        subgraph PrivateSubnet["Private Subnets (Multi-AZ)"]
            subgraph ECS_Cluster["ECS Fargate Cluster"]
                UserSvc["user-service\n(ECS Task)"]
                VenueSvc["venue-service\n(ECS Task)"]
                BookingSvc["booking-service\n(ECS Task)"]
                NotifySvc["notification-service\n(ECS Task)"]
            end

            CloudMap["AWS Cloud Map\n(seatlock.local)\n───────────────\nStable internal DNS\nAuto-register on start\nAuto-deregister on stop"]

            RDS["Amazon RDS\nPostgreSQL\n───────────────\nusers · venues · slots\nholds · bookings\nMulti-AZ standby"]

            Redis["Amazon ElastiCache\nRedis\n───────────────\nhold:{slotId}  TTL=30min\nslots:{venueId}:{date}  TTL=5s\nMulti-AZ with replica"]

            Vault["HashiCorp Vault\n(ECS Task)\n───────────────\nJWT signing secrets\nDB credentials\nRedis credentials\nAPI keys"]
        end

        subgraph ManagedServices["AWS Managed Services"]
            SQS["Amazon SQS\n(Standard Queue)\n───────────────\nHoldExpiredEvent\nBookingConfirmedEvent\nBookingCancelledEvent"]
        end
    end

    Browser -- "HTTPS" --> ALB
    ALB --> UserSvc
    ALB --> VenueSvc
    ALB --> BookingSvc

    BookingSvc -- "Service JWT\n(Cloud Map DNS)" --> VenueSvc
    VenueSvc -.->|"auto-register"| CloudMap
    BookingSvc -.->|"auto-register"| CloudMap
    UserSvc -.->|"auto-register"| CloudMap

    UserSvc --> RDS
    VenueSvc --> RDS
    BookingSvc --> RDS

    VenueSvc --> Redis
    BookingSvc --> Redis

    BookingSvc -- "publish events" --> SQS
    SQS -- "consume events" --> NotifySvc

    UserSvc --> Vault
    VenueSvc --> Vault
    BookingSvc --> Vault
    NotifySvc --> Vault

    ECR -.->|"pull image"| ECS_Cluster
```

---

## Infrastructure Components

| Component | AWS Service | Purpose |
|-----------|------------|---------|
| Compute | ECS Fargate | Serverless containers — no EC2 management |
| Load balancing | Application Load Balancer | Single public entry point; path-based routing |
| Database | RDS PostgreSQL (Multi-AZ) | Source of truth; ACID guarantees |
| Cache / Holds | ElastiCache Redis (with replica) | Sub-ms SETNX for holds; availability cache |
| Messaging | SQS Standard Queue | Async booking → notification events |
| Service discovery | AWS Cloud Map | Internal DNS (`*.seatlock.local`); ECS-native |
| Container registry | Amazon ECR | Stores built service images |
| Secrets | HashiCorp Vault | Dynamic secrets and rotation |
| CI/CD | GitHub Actions | Build, test, push, deploy on every merge to main |

---

## Network Boundaries

| Traffic | Path | Notes |
|---------|------|-------|
| External users → services | Internet → ALB → ECS (private subnet) | ALB terminates TLS; backend is HTTP inside VPC |
| Service-to-service | Cloud Map DNS (private subnet) | Never leaves VPC |
| Services → RDS | Private subnet | Security group allows port 5432 from ECS tasks only |
| Services → Redis | Private subnet | Security group allows port 6379 from ECS tasks only |
| booking-service → SQS | AWS-managed (VPC endpoint optional) | Standard SQS API |
| Services → Vault | Private subnet | Vault on ECS within same VPC |
| CI/CD → ECR / ECS | GitHub Actions → AWS API | IAM role via OIDC federation |

---

## High Availability

| Component | HA Strategy |
|-----------|------------|
| ECS tasks | Multiple tasks per service; ECS replaces failed tasks automatically |
| ALB | Multi-AZ by default |
| RDS | Multi-AZ standby; automatic failover |
| ElastiCache | Primary + read replica; automatic failover |
| SQS | Managed; inherently durable |
| Vault | Single ECS task in Phase 0; HA Vault cluster is Phase 1+ |
