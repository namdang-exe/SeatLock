package com.seatlock.booking.controller;

import com.seatlock.booking.dto.BookingHistoryResponse;
import com.seatlock.booking.dto.BookingRequest;
import com.seatlock.booking.dto.BookingResponse;
import com.seatlock.booking.dto.CancelResponse;
import com.seatlock.booking.service.BookingService;
import com.seatlock.booking.service.CancellationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {

    private final BookingService bookingService;
    private final CancellationService cancellationService;

    public BookingController(BookingService bookingService, CancellationService cancellationService) {
        this.bookingService = bookingService;
        this.cancellationService = cancellationService;
    }

    @PostMapping
    public ResponseEntity<BookingResponse> confirmBooking(
            @Valid @RequestBody BookingRequest request,
            Authentication authentication) {

        UUID userId = extractUserId(authentication);
        BookingResponse response = bookingService.confirmBooking(request.sessionId(), userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{confirmationNumber}/cancel")
    public ResponseEntity<CancelResponse> cancel(
            @PathVariable String confirmationNumber,
            Authentication authentication) {

        UUID userId = extractUserId(authentication);
        CancelResponse response = cancellationService.cancel(confirmationNumber, userId);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<BookingHistoryResponse> getHistory(Authentication authentication) {
        UUID userId = extractUserId(authentication);
        return ResponseEntity.ok(cancellationService.getHistory(userId));
    }

    private UUID extractUserId(Authentication authentication) {
        String userIdStr = (String) ((UsernamePasswordAuthenticationToken) authentication).getDetails();
        return UUID.fromString(userIdStr);
    }
}
