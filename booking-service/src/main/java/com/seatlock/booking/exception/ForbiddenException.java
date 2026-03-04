package com.seatlock.booking.exception;

public class ForbiddenException extends RuntimeException {

    public ForbiddenException() {
        super("You are not authorized to confirm this booking.");
    }
}
