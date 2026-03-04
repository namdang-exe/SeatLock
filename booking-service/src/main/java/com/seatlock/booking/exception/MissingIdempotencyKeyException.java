package com.seatlock.booking.exception;

public class MissingIdempotencyKeyException extends RuntimeException {

    public MissingIdempotencyKeyException() {
        super("Idempotency-Key header is required and must be a valid UUID");
    }
}
