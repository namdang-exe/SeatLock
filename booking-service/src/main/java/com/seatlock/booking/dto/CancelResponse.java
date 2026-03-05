package com.seatlock.booking.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CancelResponse(
        String confirmationNumber,
        Instant cancelledAt,
        List<CancelledBookingItem> bookings
) {
    public record CancelledBookingItem(
            UUID bookingId,
            UUID slotId,
            String status
    ) {}
}
