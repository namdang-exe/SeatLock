package com.seatlock.booking.event;

import org.springframework.stereotype.Component;

/**
 * No-op stub for BookingEventPublisher.
 * Will be replaced with a real SQS publisher in Stage 11 when ElasticMQ is added.
 */
@Component
public class NoOpBookingEventPublisher implements BookingEventPublisher {

    @Override
    public void publishBookingConfirmed(BookingConfirmedEvent event) {
        // Stage 11: replace with SQS publish
    }

    @Override
    public void publishHoldExpired(HoldExpiredEvent event) {
        // Stage 11: replace with SQS publish
    }
}
