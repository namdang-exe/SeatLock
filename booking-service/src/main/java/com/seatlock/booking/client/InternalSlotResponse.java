package com.seatlock.booking.client;

import java.time.Instant;
import java.util.UUID;

public record InternalSlotResponse(
        UUID slotId,
        UUID venueId,
        Instant startTime,
        String status) {
}
