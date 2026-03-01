package com.seatlock.user.dto;

import java.util.UUID;

public record RegisterResponse(UUID userId, String email) {}