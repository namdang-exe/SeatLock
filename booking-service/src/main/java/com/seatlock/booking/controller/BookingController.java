package com.seatlock.booking.controller;

import com.seatlock.booking.dto.BookingRequest;
import com.seatlock.booking.dto.BookingResponse;
import com.seatlock.booking.service.BookingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping
    public ResponseEntity<BookingResponse> confirmBooking(
            @Valid @RequestBody BookingRequest request,
            Authentication authentication) {

        String userIdStr = (String) ((UsernamePasswordAuthenticationToken) authentication).getDetails();
        UUID userId = UUID.fromString(userIdStr);

        BookingResponse response = bookingService.confirmBooking(request.sessionId(), userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
