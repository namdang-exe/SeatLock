package com.seatlock.common.exception;

public class SlotNotFoundException extends DomainException {

    public SlotNotFoundException() {
        super("SLOT_NOT_FOUND", 404, "Slot not found.");
    }

    public SlotNotFoundException(String message) {
        super("SLOT_NOT_FOUND", 404, message);
    }
}
