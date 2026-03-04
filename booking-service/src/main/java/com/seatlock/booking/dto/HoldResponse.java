package com.seatlock.booking.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record HoldResponse(
        UUID sessionId,
        Instant expiresAt,
        List<HoldItemResponse> holds) {

    public record HoldItemResponse(UUID holdId, UUID slotId) {
    }
}
