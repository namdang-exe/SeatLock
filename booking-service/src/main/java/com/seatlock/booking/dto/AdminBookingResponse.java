package com.seatlock.booking.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AdminBookingResponse(List<AdminBookingItem> bookings) {

    public record AdminBookingItem(
            UUID bookingId,
            UUID sessionId,
            String confirmationNumber,
            UUID userId,
            UUID slotId,
            String status,
            Instant createdAt,
            Instant startTime
    ) {}
}
