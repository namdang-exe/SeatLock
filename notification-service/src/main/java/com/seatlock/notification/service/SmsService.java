package com.seatlock.notification.service;

import com.seatlock.notification.event.BookingCancelledEvent;
import com.seatlock.notification.event.BookingConfirmedEvent;
import com.seatlock.notification.event.HoldExpiredEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * SMS notification stub — logs to console locally.
 * Real Twilio integration will be wired in Stage 16.
 */
@Service
public class SmsService {

    private static final Logger log = LoggerFactory.getLogger(SmsService.class);

    public void sendBookingConfirmed(BookingConfirmedEvent event) {
        log.info("[SMS stub] Booking confirmed: {} for user {}",
                event.confirmationNumber(), event.userId());
    }

    public void sendBookingCancelled(BookingCancelledEvent event) {
        log.info("[SMS stub] Booking cancelled: {} for user {}",
                event.confirmationNumber(), event.userId());
    }

    public void sendHoldExpired(HoldExpiredEvent event) {
        log.info("[SMS stub] Hold expired for session {} user {}",
                event.sessionId(), event.userId());
    }
}
