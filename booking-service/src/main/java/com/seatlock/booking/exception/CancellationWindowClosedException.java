package com.seatlock.booking.exception;

public class CancellationWindowClosedException extends RuntimeException {
    public CancellationWindowClosedException() {
        super("Cancellation window has closed. Bookings must be cancelled more than 24 hours before the slot start time.");
    }
}
