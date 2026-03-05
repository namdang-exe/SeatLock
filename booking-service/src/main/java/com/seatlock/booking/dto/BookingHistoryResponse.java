package com.seatlock.booking.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BookingHistoryResponse(List<BookingSession> sessions) {

    public record BookingSession(
            String confirmationNumber,
            UUID sessionId,
            Instant createdAt,
            List<BookingSlot> bookings
    ) {
        public record BookingSlot(
                UUID bookingId,
                UUID slotId,
                Instant startTime,
                String venueName,
                String status,
                Instant cancelledAt
        ) {}
    }
}
