package com.seatlock.booking.exception;

public class SlotNotFoundException extends RuntimeException {

    public SlotNotFoundException() {
        super("One or more requested slot IDs were not found");
    }
}
