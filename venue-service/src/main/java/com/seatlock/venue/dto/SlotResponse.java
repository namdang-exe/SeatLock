package com.seatlock.venue.dto;

import java.time.Instant;

public record SlotResponse(
        String slotId,
        String venueId,
        Instant startTime,
        Instant endTime,
        String status
) {}
