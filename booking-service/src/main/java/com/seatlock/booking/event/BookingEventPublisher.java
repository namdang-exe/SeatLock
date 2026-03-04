package com.seatlock.booking.event;

public interface BookingEventPublisher {

    void publishBookingConfirmed(BookingConfirmedEvent event);
}
