package com.seatlock.booking.exception;

public class HoldMismatchException extends RuntimeException {

    public HoldMismatchException() {
        super("Hold state mismatch. Refresh availability and create a new hold.");
    }
}
