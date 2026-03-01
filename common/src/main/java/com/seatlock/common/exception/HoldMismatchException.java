package com.seatlock.common.exception;

public class HoldMismatchException extends DomainException {

    public HoldMismatchException() {
        super("HOLD_MISMATCH", 409, "Hold state mismatch. Refresh availability and create a new hold.");
    }

    public HoldMismatchException(String message) {
        super("HOLD_MISMATCH", 409, message);
    }
}
