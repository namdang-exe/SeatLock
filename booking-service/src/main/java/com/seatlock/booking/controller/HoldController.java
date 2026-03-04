package com.seatlock.booking.controller;

import com.seatlock.booking.dto.HoldRequest;
import com.seatlock.booking.dto.HoldResponse;
import com.seatlock.booking.exception.MissingIdempotencyKeyException;
import com.seatlock.booking.service.HoldService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/holds")
public class HoldController {

    private final HoldService holdService;

    public HoldController(HoldService holdService) {
        this.holdService = holdService;
    }

    @PostMapping
    public ResponseEntity<HoldResponse> createHold(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKeyHeader,
            @Valid @RequestBody HoldRequest request,
            Authentication authentication) {

        // Step 1: Validate Idempotency-Key header
        if (idempotencyKeyHeader == null || idempotencyKeyHeader.isBlank()) {
            throw new MissingIdempotencyKeyException();
        }
        UUID sessionId;
        try {
            sessionId = UUID.fromString(idempotencyKeyHeader);
        } catch (IllegalArgumentException e) {
            throw new MissingIdempotencyKeyException();
        }

        String userIdStr = (String) ((UsernamePasswordAuthenticationToken) authentication).getDetails();
        UUID userId = UUID.fromString(userIdStr);

        HoldResponse response = holdService.createHold(userId, request.slotIds(), sessionId);
        return ResponseEntity.ok(response);
    }
}
