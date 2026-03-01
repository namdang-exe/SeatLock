package com.seatlock.common.exception;

public class CancellationWindowClosedException extends DomainException {

    public CancellationWindowClosedException() {
        super("CANCELLATION_WINDOW_CLOSED", 409,
                "Cancellation is not allowed within 24 hours of the reservation.");
    }

    public CancellationWindowClosedException(String message) {
        super("CANCELLATION_WINDOW_CLOSED", 409, message);
    }
}
