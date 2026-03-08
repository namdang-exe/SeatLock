package com.seatlock.booking.client;

import java.time.Instant;
import java.util.UUID;

public record InternalSlotResponse(
        UUID slotId,
        UUID venueId,
        String venueName,
        Instant startTime,
        String status) {
}
