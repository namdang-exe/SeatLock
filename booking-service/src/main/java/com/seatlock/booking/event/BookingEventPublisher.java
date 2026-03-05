package com.seatlock.booking.event;

public interface BookingEventPublisher {

    void publishBookingConfirmed(BookingConfirmedEvent event);

    void publishHoldExpired(HoldExpiredEvent event);

    void publishBookingCancelled(BookingCancelledEvent event);
}
