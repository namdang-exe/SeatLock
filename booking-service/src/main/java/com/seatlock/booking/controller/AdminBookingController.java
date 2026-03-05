package com.seatlock.booking.controller;

import com.seatlock.booking.dto.AdminBookingResponse;
import com.seatlock.booking.service.CancellationService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/venues")
public class AdminBookingController {

    private final CancellationService cancellationService;

    public AdminBookingController(CancellationService cancellationService) {
        this.cancellationService = cancellationService;
    }

    @GetMapping("/{venueId}/bookings")
    public ResponseEntity<AdminBookingResponse> getAdminBookings(
            @PathVariable UUID venueId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        return ResponseEntity.ok(cancellationService.getAdminBookings(venueId, date));
    }
}
