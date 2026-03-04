package com.seatlock.booking.exception;

import java.util.UUID;

public class SessionNotFoundException extends RuntimeException {

    public SessionNotFoundException(UUID sessionId) {
        super("No active holds found for session: " + sessionId);
    }
}
