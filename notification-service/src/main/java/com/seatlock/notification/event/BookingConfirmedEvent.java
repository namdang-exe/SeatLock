package com.seatlock.notification.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BookingConfirmedEvent(
        String confirmationNumber,
        UUID sessionId,
        UUID userId,
        List<UUID> slotIds,
        Instant timestamp) {
}
