package com.seatlock.common.exception;

public class InvalidCredentialsException extends DomainException {

    public InvalidCredentialsException() {
        super("INVALID_CREDENTIALS", 401, "Invalid email or password.");
    }

    public InvalidCredentialsException(String message) {
        super("INVALID_CREDENTIALS", 401, message);
    }
}
