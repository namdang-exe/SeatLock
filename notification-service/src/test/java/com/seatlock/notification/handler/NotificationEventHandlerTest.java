package com.seatlock.notification.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.seatlock.notification.event.BookingCancelledEvent;
import com.seatlock.notification.event.BookingConfirmedEvent;
import com.seatlock.notification.event.HoldExpiredEvent;
import com.seatlock.notification.service.EmailService;
import com.seatlock.notification.service.SmsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class NotificationEventHandlerTest {

    @Mock
    private EmailService emailService;

    @Mock
    private SmsService smsService;

    private NotificationEventHandler handler;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

    @BeforeEach
    void setUp() {
        handler = new NotificationEventHandler(emailService, smsService, objectMapper);
    }

    @Test
    void bookingConfirmedDispatchesToEmailAndSms() throws Exception {
        var event = new BookingConfirmedEvent(
                "SL-20260305-0001", UUID.randomUUID(), UUID.randomUUID(),
                List.of(UUID.randomUUID()), Instant.now());
        handler.handle(buildEnvelope("BookingConfirmed", event));

        verify(emailService).sendBookingConfirmed(any(BookingConfirmedEvent.class));
        verify(smsService).sendBookingConfirmed(any(BookingConfirmedEvent.class));
    }

    @Test
    void bookingCancelledDispatchesToEmailAndSms() throws Exception {
        var event = new BookingCancelledEvent(
                "SL-20260305-0002", UUID.randomUUID(), UUID.randomUUID(),
                List.of(UUID.randomUUID()), Instant.now());
        handler.handle(buildEnvelope("BookingCancelled", event));

        verify(emailService).sendBookingCancelled(any(BookingCancelledEvent.class));
        verify(smsService).sendBookingCancelled(any(BookingCancelledEvent.class));
    }

    @Test
    void holdExpiredDispatchesToEmailAndSms() throws Exception {
        var event = new HoldExpiredEvent(
                UUID.randomUUID(), UUID.randomUUID(),
                List.of(UUID.randomUUID()), Instant.now());
        handler.handle(buildEnvelope("HoldExpired", event));

        verify(emailService).sendHoldExpired(any(HoldExpiredEvent.class));
        verify(smsService).sendHoldExpired(any(HoldExpiredEvent.class));
    }

    @Test
    void unknownEventTypeIsDroppedWithoutError() throws Exception {
        handler.handle("{\"type\":\"UnknownEvent\",\"payload\":{}}");
        verifyNoInteractions(emailService, smsService);
    }

    private String buildEnvelope(String type, Object payload) throws Exception {
        return objectMapper.writeValueAsString(Map.of("type", type, "payload", payload));
    }
}
