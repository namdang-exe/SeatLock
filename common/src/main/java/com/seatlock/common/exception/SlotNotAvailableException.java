package com.seatlock.common.exception;

public class SlotNotAvailableException extends DomainException {

    public SlotNotAvailableException() {
        super("SLOT_NOT_AVAILABLE", 409, "One or more requested slots are not available.");
    }

    public SlotNotAvailableException(String message) {
        super("SLOT_NOT_AVAILABLE", 409, message);
    }
}
