package com.seatlock.booking.exception;

public class BookingNotFoundException extends RuntimeException {
    public BookingNotFoundException(String confirmationNumber) {
        super("No booking found for confirmation number: " + confirmationNumber);
    }
}
