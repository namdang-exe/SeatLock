package com.seatlock.booking.exception;

public class RedisUnavailableException extends RuntimeException {

    public RedisUnavailableException(Throwable cause) {
        super("Redis is unavailable — hold service temporarily unavailable", cause);
    }
}
