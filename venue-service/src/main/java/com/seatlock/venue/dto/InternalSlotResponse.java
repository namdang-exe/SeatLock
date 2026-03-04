package com.seatlock.venue.dto;

import java.time.Instant;
import java.util.UUID;

public record InternalSlotResponse(UUID slotId, UUID venueId, Instant startTime, String status) {
}
