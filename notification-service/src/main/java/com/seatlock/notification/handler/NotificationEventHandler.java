package com.seatlock.notification.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatlock.notification.event.BookingCancelledEvent;
import com.seatlock.notification.event.BookingConfirmedEvent;
import com.seatlock.notification.event.EventEnvelope;
import com.seatlock.notification.event.HoldExpiredEvent;
import com.seatlock.notification.service.EmailService;
import com.seatlock.notification.service.SmsService;
import io.awspring.cloud.sqs.annotation.SqsListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Consumes events from the SQS queue and dispatches to EmailService and SmsService.
 *
 * Throws on unrecoverable processing errors — Spring Cloud AWS SQS will retry
 * up to maxReceiveCount (3) before moving the message to the DLQ.
 * Unknown event types are logged and silently dropped (no retry needed).
 */
@Service
public class NotificationEventHandler {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventHandler.class);

    private final EmailService emailService;
    private final SmsService smsService;
    private final ObjectMapper objectMapper;

    public NotificationEventHandler(EmailService emailService,
                                    SmsService smsService,
                                    ObjectMapper objectMapper) {
        this.emailService = emailService;
        this.smsService = smsService;
        this.objectMapper = objectMapper;
    }

    @SqsListener("${seatlock.notifications.queue-name}")
    public void handle(String messageBody) throws Exception {
        EventEnvelope envelope = objectMapper.readValue(messageBody, EventEnvelope.class);
        log.debug("Received event type: {}", envelope.type());

        switch (envelope.type()) {
            case "BookingConfirmed" -> {
                BookingConfirmedEvent event =
                        objectMapper.treeToValue(envelope.payload(), BookingConfirmedEvent.class);
                emailService.sendBookingConfirmed(event);
                smsService.sendBookingConfirmed(event);
            }
            case "BookingCancelled" -> {
                BookingCancelledEvent event =
                        objectMapper.treeToValue(envelope.payload(), BookingCancelledEvent.class);
                emailService.sendBookingCancelled(event);
                smsService.sendBookingCancelled(event);
            }
            case "HoldExpired" -> {
                HoldExpiredEvent event =
                        objectMapper.treeToValue(envelope.payload(), HoldExpiredEvent.class);
                emailService.sendHoldExpired(event);
                smsService.sendHoldExpired(event);
            }
            default -> log.warn("Unknown event type '{}' — dropping message", envelope.type());
        }
    }
}
