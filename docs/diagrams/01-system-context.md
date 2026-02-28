# Diagram 01 — System Context

## Overview

SeatLock as a black box — who uses it and what external systems it depends on.

```mermaid
flowchart TB
    subgraph Actors
        User["Registered User\n───────────────\nBrowses venue slots\nCreates holds\nConfirms bookings\nCancels bookings\nViews booking history"]
        Admin["Venue Admin\n───────────────\nCreates venues\nManages time slots\nViews confirmed bookings"]
    end

    subgraph SeatLock["SeatLock — Distributed Reservation Platform"]
        direction TB
        Core["4 Services\n───────────────\nuser-service\nvenue-service\nbooking-service\nnotification-service"]
    end

    subgraph External["External Systems"]
        Email["Email Provider\n(e.g. SendGrid)\n───────────────\nTransactional email\nConfirmation · Expiry · Cancellation"]
        SMS["SMS Provider\n(e.g. Twilio)\n───────────────\nSMS notifications"]
        Vault["HashiCorp Vault\n───────────────\nSecrets management\nJWT keys · DB credentials\nAPI keys · Redis credentials"]
    end

    subgraph AWS["AWS Infrastructure"]
        Infra["ECS Fargate · RDS PostgreSQL\nElastiCache Redis · ALB\nSQS · Cloud Map · ECR"]
    end

    User -- "HTTPS\nBrowse · Hold · Confirm\nCancel · View history" --> SeatLock
    Admin -- "HTTPS\nVenue CRUD · Slot management\nView bookings" --> SeatLock

    SeatLock -- "SMTP / API\nBooking confirmed email\nHold expired email\nCancellation email" --> Email
    SeatLock -- "API\nBooking confirmed SMS\nHold expired SMS\nCancellation SMS" --> SMS
    SeatLock -- "Fetch secrets at startup\nPeriodic rotation at runtime" --> Vault
    SeatLock -- "Deployed on" --> AWS
```

---

## External Actor Summary

| Actor | Type | Interactions |
|-------|------|-------------|
| Registered User | Human | Browse slots, create holds, confirm bookings, cancel bookings, view booking history |
| Venue Admin | Human | Create/deactivate venues, manage slots, view confirmed bookings |
| Email Provider | External system | Receives send requests from notification-service via SMTP or REST API |
| SMS Provider | External system | Receives send requests from notification-service via REST API |
| HashiCorp Vault | External system | Provides secrets at startup; supports runtime secret rotation |
| AWS | Cloud platform | Provides all compute, storage, networking, and messaging infrastructure |

---

## What SeatLock Does Not Own

| Concern | Out of scope | Notes |
|---------|-------------|-------|
| Payment processing | ✗ | Booking confirmation is the terminal action; payment is a future phase |
| Email/SMS delivery infrastructure | ✗ | Delegated to third-party providers |
| Identity providers (OAuth, SSO) | ✗ | Phase 0 uses JWT with internal user registry only |
| Venue physical access control | ✗ | SeatLock issues a confirmation number; physical enforcement is the venue's concern |
