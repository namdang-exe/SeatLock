package com.seatlock.booking.redis;

import java.time.Instant;
import java.util.UUID;

public record HoldPayload(
        UUID holdId,
        UUID userId,
        UUID sessionId,
        Instant expiresAt) {
}
