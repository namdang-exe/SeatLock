package com.seatlock.notification.service;

import com.seatlock.notification.event.BookingCancelledEvent;
import com.seatlock.notification.event.BookingConfirmedEvent;
import com.seatlock.notification.event.HoldExpiredEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final String defaultRecipient;

    public EmailService(JavaMailSender mailSender,
                        @Value("${seatlock.notifications.from-address}") String fromAddress,
                        @Value("${seatlock.notifications.default-recipient}") String defaultRecipient) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        this.defaultRecipient = defaultRecipient;
    }

    public void sendBookingConfirmed(BookingConfirmedEvent event) {
        send("Booking confirmed \u2014 " + event.confirmationNumber(),
             "Your booking " + event.confirmationNumber() + " is confirmed.\n" +
             "Slots booked: " + event.slotIds().size() + "\n" +
             "Session ID: " + event.sessionId());
    }

    public void sendBookingCancelled(BookingCancelledEvent event) {
        send("Booking cancelled \u2014 " + event.confirmationNumber(),
             "Your booking " + event.confirmationNumber() + " has been cancelled.\n" +
             "Slots cancelled: " + event.cancelledSlotIds().size());
    }

    public void sendHoldExpired(HoldExpiredEvent event) {
        send("Your hold has expired",
             "Your hold for session " + event.sessionId() + " has expired.\n" +
             "Please browse available slots and create a new booking.");
    }

    private void send(String subject, String body) {
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(fromAddress);
        msg.setTo(defaultRecipient);
        msg.setSubject(subject);
        msg.setText(body);
        mailSender.send(msg);
        log.info("Email sent: subject='{}' to='{}'", subject, defaultRecipient);
    }
}
