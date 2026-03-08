package com.seatlock.booking.service;

import com.seatlock.booking.client.SlotVerificationClient;
import com.seatlock.booking.domain.Booking;
import com.seatlock.booking.domain.BookingStatus;
import com.seatlock.booking.domain.Hold;
import com.seatlock.booking.domain.HoldStatus;
import com.seatlock.booking.dto.BookingResponse;
import com.seatlock.booking.event.BookingEventPublisher;
import com.seatlock.booking.exception.ForbiddenException;
import com.seatlock.booking.exception.HoldExpiredException;
import com.seatlock.booking.exception.HoldMismatchException;
import com.seatlock.booking.exception.SessionNotFoundException;
import com.seatlock.booking.redis.HoldPayload;
import com.seatlock.booking.redis.RedisHoldRepository;
import com.seatlock.booking.repository.BookingRepository;
import com.seatlock.booking.repository.HoldRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock BookingRepository bookingRepository;
    @Mock HoldRepository holdRepository;
    @Mock RedisHoldRepository redisHoldRepository;
    @Mock SlotVerificationClient slotVerificationClient;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock JdbcTemplate venueJdbcTemplate;
    @Mock PlatformTransactionManager txManager;
    @Mock BookingEventPublisher eventPublisher;

    ConfirmationNumberGenerator confirmationNumberGenerator = new ConfirmationNumberGenerator();
    BookingService bookingService;

    UUID userId    = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    UUID slotId    = UUID.randomUUID();
    UUID holdId    = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        bookingService = new BookingService(bookingRepository, holdRepository, redisHoldRepository,
                slotVerificationClient, jdbcTemplate, venueJdbcTemplate, txManager,
                confirmationNumberGenerator, eventPublisher);

        lenient().when(txManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        lenient().when(jdbcTemplate.update(any(PreparedStatementCreator.class))).thenReturn(1);
        lenient().when(slotVerificationClient.verify(anyList())).thenReturn(List.of());
    }

    @Test
    void idempotencyCheck_existingConfirmedBookings_returnsExistingData() {
        Booking existing = bookingWith(sessionId, userId, slotId, "SL-20260303-1234");
        when(bookingRepository.findBySessionIdAndStatus(sessionId, BookingStatus.CONFIRMED))
                .thenReturn(List.of(existing));

        BookingResponse response = bookingService.confirmBooking(sessionId, userId);

        assertThat(response.confirmationNumber()).isEqualTo("SL-20260303-1234");
        assertThat(response.sessionId()).isEqualTo(sessionId);
        verify(holdRepository, never()).findBySessionIdAndStatus(any(), any());
    }

    @Test
    void sessionNotFound_noActiveHolds_throwsSessionNotFoundException() {
        when(bookingRepository.findBySessionIdAndStatus(sessionId, BookingStatus.CONFIRMED))
                .thenReturn(List.of());
        when(holdRepository.findBySessionIdAndStatus(sessionId, HoldStatus.ACTIVE))
                .thenReturn(List.of());

        assertThatThrownBy(() -> bookingService.confirmBooking(sessionId, userId))
                .isInstanceOf(SessionNotFoundException.class);
    }

    @Test
    void forbidden_holdBelongsToDifferentUser_throwsForbiddenException() {
        UUID otherUserId = UUID.randomUUID();
        Hold hold = holdWith(holdId, slotId, otherUserId, sessionId);

        when(bookingRepository.findBySessionIdAndStatus(sessionId, BookingStatus.CONFIRMED))
                .thenReturn(List.of());
        when(holdRepository.findBySessionIdAndStatus(sessionId, HoldStatus.ACTIVE))
                .thenReturn(List.of(hold));

        assertThatThrownBy(() -> bookingService.confirmBooking(sessionId, userId))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void holdExpired_redisKeyAbsent_throwsHoldExpiredException() {
        Hold hold = holdWith(holdId, slotId, userId, sessionId);

        when(bookingRepository.findBySessionIdAndStatus(sessionId, BookingStatus.CONFIRMED))
                .thenReturn(List.of());
        when(holdRepository.findBySessionIdAndStatus(sessionId, HoldStatus.ACTIVE))
                .thenReturn(List.of(hold));
        when(redisHoldRepository.getHold(slotId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookingService.confirmBooking(sessionId, userId))
                .isInstanceOf(HoldExpiredException.class);
    }

    @Test
    void holdMismatch_redisHoldIdDiffers_throwsHoldMismatchException() {
        Hold hold = holdWith(holdId, slotId, userId, sessionId);
        UUID differentHoldId = UUID.randomUUID();
        HoldPayload mismatchedPayload = new HoldPayload(differentHoldId, userId, sessionId,
                Instant.now().plusSeconds(1800));

        when(bookingRepository.findBySessionIdAndStatus(sessionId, BookingStatus.CONFIRMED))
                .thenReturn(List.of());
        when(holdRepository.findBySessionIdAndStatus(sessionId, HoldStatus.ACTIVE))
                .thenReturn(List.of(hold));
        when(redisHoldRepository.getHold(slotId)).thenReturn(Optional.of(mismatchedPayload));

        assertThatThrownBy(() -> bookingService.confirmBooking(sessionId, userId))
                .isInstanceOf(HoldMismatchException.class);
    }

    @Test
    void happyPath_returnsBookingResponseWithConfirmationNumber() {
        Hold hold = holdWith(holdId, slotId, userId, sessionId);
        HoldPayload payload = new HoldPayload(holdId, userId, sessionId,
                Instant.now().plusSeconds(1800));

        when(bookingRepository.findBySessionIdAndStatus(sessionId, BookingStatus.CONFIRMED))
                .thenReturn(List.of());
        when(holdRepository.findBySessionIdAndStatus(sessionId, HoldStatus.ACTIVE))
                .thenReturn(List.of(hold));
        when(redisHoldRepository.getHold(slotId)).thenReturn(Optional.of(payload));
        when(bookingRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(holdRepository.updateStatusBySessionId(eq(sessionId), eq(HoldStatus.ACTIVE), eq(HoldStatus.CONFIRMED)))
                .thenReturn(1);

        BookingResponse response = bookingService.confirmBooking(sessionId, userId);

        assertThat(response.confirmationNumber()).matches("SL-\\d{8}-\\d{4}");
        assertThat(response.sessionId()).isEqualTo(sessionId);
        assertThat(response.bookings()).hasSize(1);
        assertThat(response.bookings().get(0).slotId()).isEqualTo(slotId);
        assertThat(response.bookings().get(0).status()).isEqualTo("CONFIRMED");

        // Redis cleanup happens after Postgres commit
        verify(redisHoldRepository).del(slotId);
    }

    // --- helpers ---

    private Hold holdWith(UUID holdId, UUID slotId, UUID userId, UUID sessionId) {
        Hold h = new Hold();
        h.setSlotId(slotId);
        h.setUserId(userId);
        h.setSessionId(sessionId);
        h.setExpiresAt(Instant.now().plusSeconds(1800));
        // Force holdId via reflection since it's set in constructor
        try {
            var field = Hold.class.getDeclaredField("holdId");
            field.setAccessible(true);
            field.set(h, holdId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return h;
    }

    private Booking bookingWith(UUID sessionId, UUID userId, UUID slotId, String confirmationNumber) {
        Booking b = new Booking();
        b.setSessionId(sessionId);
        b.setUserId(userId);
        b.setSlotId(slotId);
        b.setConfirmationNumber(confirmationNumber);
        return b;
    }
}
