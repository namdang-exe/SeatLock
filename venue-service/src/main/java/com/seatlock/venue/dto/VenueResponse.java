package com.seatlock.venue.dto;

public record VenueResponse(
        String venueId,
        String name,
        String address,
        String city,
        String state,
        String status
) {}
