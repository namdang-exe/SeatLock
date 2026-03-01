package com.seatlock.common.exception;

public class BookingNotFoundException extends DomainException {

    public BookingNotFoundException() {
        super("BOOKING_NOT_FOUND", 404, "Booking not found.");
    }

    public BookingNotFoundException(String message) {
        super("BOOKING_NOT_FOUND", 404, message);
    }
}
