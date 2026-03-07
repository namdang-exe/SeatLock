package com.seatlock.booking.exception;

public class VenueServiceUnavailableException extends RuntimeException {

    public VenueServiceUnavailableException() {
        super("Venue service is currently unavailable. Please try again shortly.");
    }

    public VenueServiceUnavailableException(Throwable cause) {
        super("Venue service is currently unavailable. Please try again shortly.", cause);
    }
}
