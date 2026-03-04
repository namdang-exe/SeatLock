package com.seatlock.booking.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record BookingRequest(@NotNull(message = "sessionId is required") UUID sessionId) {
}
