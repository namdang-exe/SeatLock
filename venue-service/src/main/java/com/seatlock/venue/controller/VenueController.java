package com.seatlock.venue.controller;

import com.seatlock.venue.dto.SlotResponse;
import com.seatlock.venue.dto.VenueResponse;
import com.seatlock.venue.service.VenueService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/venues")
public class VenueController {

    private final VenueService venueService;

    public VenueController(VenueService venueService) {
        this.venueService = venueService;
    }

    @GetMapping
    public List<VenueResponse> getActiveVenues() {
        return venueService.getActiveVenues();
    }

    @GetMapping("/{venueId}/slots")
    public List<SlotResponse> getSlots(
            @PathVariable UUID venueId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String status) {
        return venueService.getSlots(venueId, date, status);
    }
}
