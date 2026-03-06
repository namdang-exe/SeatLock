package com.seatlock.booking.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

import java.util.Map;

/**
 * Publishes booking lifecycle events to SQS using a typed envelope:
 * {"type": "BookingConfirmed", "payload": {...}}
 *
 * Fire-and-forget per ADR-005 — failures are logged but never propagated to the caller.
 */
@Component
@Primary
public class SqsBookingEventPublisher implements BookingEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(SqsBookingEventPublisher.class);

    private final SqsAsyncClient sqsClient;
    private final ObjectMapper objectMapper;
    private final String queueUrl;

    public SqsBookingEventPublisher(SqsAsyncClient sqsClient,
                                    ObjectMapper objectMapper,
                                    @Value("${seatlock.sqs.queue-url}") String queueUrl) {
        this.sqsClient = sqsClient;
        this.objectMapper = objectMapper;
        this.queueUrl = queueUrl;
    }

    @Override
    public void publishBookingConfirmed(BookingConfirmedEvent event) {
        publish("BookingConfirmed", event);
    }

    @Override
    public void publishHoldExpired(HoldExpiredEvent event) {
        publish("HoldExpired", event);
    }

    @Override
    public void publishBookingCancelled(BookingCancelledEvent event) {
        publish("BookingCancelled", event);
    }

    private void publish(String type, Object payload) {
        String body;
        try {
            body = objectMapper.writeValueAsString(Map.of("type", type, "payload", payload));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize {} event: {}", type, e.getMessage());
            return;
        }
        sqsClient.sendMessage(req -> req.queueUrl(queueUrl).messageBody(body))
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish {} event to SQS: {}", type, ex.getMessage());
                    }
                });
    }
}
