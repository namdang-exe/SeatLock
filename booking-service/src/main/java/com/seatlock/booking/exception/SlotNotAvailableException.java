package com.seatlock.booking.exception;

import java.util.List;
import java.util.UUID;

public class SlotNotAvailableException extends RuntimeException {

    private final List<UUID> unavailableSlotIds;

    public SlotNotAvailableException(List<UUID> unavailableSlotIds) {
        super("One or more requested slots are not available");
        this.unavailableSlotIds = unavailableSlotIds;
    }

    public List<UUID> getUnavailableSlotIds() {
        return unavailableSlotIds;
    }
}
