package com.seatlock.notification.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record HoldExpiredEvent(
        UUID sessionId,
        UUID userId,
        List<UUID> expiredSlotIds,
        Instant timestamp) {
}
