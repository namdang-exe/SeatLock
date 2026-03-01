package com.seatlock.common.exception;

public class VenueNotFoundException extends DomainException {

    public VenueNotFoundException() {
        super("VENUE_NOT_FOUND", 404, "Venue not found.");
    }

    public VenueNotFoundException(String message) {
        super("VENUE_NOT_FOUND", 404, message);
    }
}
