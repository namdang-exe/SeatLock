package com.seatlock.notification.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BookingCancelledEvent(
        String confirmationNumber,
        UUID sessionId,
        UUID userId,
        List<UUID> cancelledSlotIds,
        Instant timestamp) {
}
