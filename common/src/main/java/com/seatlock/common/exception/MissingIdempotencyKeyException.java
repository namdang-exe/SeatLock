package com.seatlock.common.exception;

public class MissingIdempotencyKeyException extends DomainException {

    public MissingIdempotencyKeyException() {
        super("MISSING_IDEMPOTENCY_KEY", 400, "Idempotency-Key header is required.");
    }

    public MissingIdempotencyKeyException(String message) {
        super("MISSING_IDEMPOTENCY_KEY", 400, message);
    }
}
