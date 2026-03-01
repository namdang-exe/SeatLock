package com.seatlock.venue.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateVenueRequest(
        @NotBlank String name,
        @NotBlank String address,
        @NotBlank String city,
        @NotBlank String state
) {}
