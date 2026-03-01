package com.seatlock.venue.controller;

import com.seatlock.venue.dto.CreateVenueRequest;
import com.seatlock.venue.dto.GenerateSlotsRequest;
import com.seatlock.venue.dto.SlotResponse;
import com.seatlock.venue.dto.UpdateVenueStatusRequest;
import com.seatlock.venue.dto.VenueResponse;
import com.seatlock.venue.service.VenueService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/venues")
public class AdminVenueController {

    private final VenueService venueService;

    public AdminVenueController(VenueService venueService) {
        this.venueService = venueService;
    }

    @PostMapping
    public ResponseEntity<VenueResponse> createVenue(@Valid @RequestBody CreateVenueRequest req) {
        return ResponseEntity.status(201).body(venueService.createVenue(req));
    }

    @PatchMapping("/{venueId}/status")
    public VenueResponse updateVenueStatus(@PathVariable UUID venueId,
                                           @Valid @RequestBody UpdateVenueStatusRequest req) {
        return venueService.updateVenueStatus(venueId, req);
    }

    @PostMapping("/{venueId}/slots/generate")
    public ResponseEntity<List<SlotResponse>> generateSlots(@PathVariable UUID venueId,
                                                            @Valid @RequestBody GenerateSlotsRequest req) {
        return ResponseEntity.status(201).body(venueService.generateSlots(venueId, req));
    }
}
