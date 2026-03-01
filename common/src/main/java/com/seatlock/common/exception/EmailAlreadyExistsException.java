package com.seatlock.common.exception;

public class EmailAlreadyExistsException extends DomainException {

    public EmailAlreadyExistsException() {
        super("EMAIL_ALREADY_EXISTS", 409, "An account with this email already exists.");
    }

    public EmailAlreadyExistsException(String message) {
        super("EMAIL_ALREADY_EXISTS", 409, message);
    }
}
