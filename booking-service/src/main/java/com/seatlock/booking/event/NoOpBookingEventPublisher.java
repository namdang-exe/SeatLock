package com.seatlock.booking.event;

/**
 * No-op implementation of BookingEventPublisher for use in tests that
 * need to override the primary SqsBookingEventPublisher bean.
 * Not registered as a Spring component — instantiate manually or via @TestConfiguration.
 */
public class NoOpBookingEventPublisher implements BookingEventPublisher {

    @Override
    public void publishBookingConfirmed(BookingConfirmedEvent event) {
    }

    @Override
    public void publishHoldExpired(HoldExpiredEvent event) {
    }

    @Override
    public void publishBookingCancelled(BookingCancelledEvent event) {
    }
}
