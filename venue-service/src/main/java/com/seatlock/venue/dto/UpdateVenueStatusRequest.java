package com.seatlock.venue.dto;

import com.seatlock.venue.domain.VenueStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateVenueStatusRequest(
        @NotNull VenueStatus status
) {}
