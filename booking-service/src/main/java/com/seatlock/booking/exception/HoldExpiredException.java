package com.seatlock.booking.exception;

public class HoldExpiredException extends RuntimeException {

    public HoldExpiredException() {
        super("Your hold has expired. Please start a new hold.");
    }
}
