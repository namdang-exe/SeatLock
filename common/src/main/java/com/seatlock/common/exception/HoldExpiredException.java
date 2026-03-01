package com.seatlock.common.exception;

public class HoldExpiredException extends DomainException {

    public HoldExpiredException() {
        super("HOLD_EXPIRED", 409, "One or more holds have expired. Please create a new hold.");
    }

    public HoldExpiredException(String message) {
        super("HOLD_EXPIRED", 409, message);
    }
}
