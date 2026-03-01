package com.seatlock.common.exception;

public class SessionNotFoundException extends DomainException {

    public SessionNotFoundException() {
        super("SESSION_NOT_FOUND", 404, "No active hold session found.");
    }

    public SessionNotFoundException(String message) {
        super("SESSION_NOT_FOUND", 404, message);
    }
}
