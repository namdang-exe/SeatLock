package com.seatlock.booking.dto;

import java.util.List;
import java.util.UUID;

public record BookingResponse(
        String confirmationNumber,
        UUID sessionId,
        List<BookingItemResponse> bookings) {

    public record BookingItemResponse(UUID bookingId, UUID slotId, String status) {
    }
}
