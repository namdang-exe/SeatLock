package com.seatlock.common.exception;

public class ForbiddenException extends DomainException {

    public ForbiddenException() {
        super("FORBIDDEN", 403, "You do not have permission to perform this action.");
    }

    public ForbiddenException(String message) {
        super("FORBIDDEN", 403, message);
    }
}
