package com.seatlock.venue.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record GenerateSlotsRequest(
        @NotNull LocalDate fromDate,
        @NotNull LocalDate toDate
) {}
