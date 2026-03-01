package com.seatlock.common.exception;

public class ServiceUnavailableException extends DomainException {

    public ServiceUnavailableException() {
        super("SERVICE_UNAVAILABLE", 503, "Service is temporarily unavailable. Please try again later.");
    }

    public ServiceUnavailableException(String message) {
        super("SERVICE_UNAVAILABLE", 503, message);
    }
}
