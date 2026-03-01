package com.seatlock.common.exception;

public class ValidationException extends DomainException {

    public ValidationException() {
        super("VALIDATION_ERROR", 400, "Request validation failed.");
    }

    public ValidationException(String message) {
        super("VALIDATION_ERROR", 400, message);
    }
}
