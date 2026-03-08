package com.seatlock.booking.service;

import com.seatlock.booking.dto.CancelResponse;
import com.seatlock.booking.event.BookingCancelledEvent;
import com.seatlock.booking.event.BookingEventPublisher;
import com.seatlock.booking.exception.BookingNotFoundException;
import com.seatlock.booking.exception.CancellationWindowClosedException;
import com.seatlock.booking.exception.ForbiddenException;
import com.seatlock.booking.redis.RedisHoldRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CancellationServiceTest {

    @Mock JdbcTemplate jdbcTemplate;
    @Mock JdbcTemplate venueJdbcTemplate;
    @Mock PlatformTransactionManager txManager;
    @Mock RedisHoldRepository redisHoldRepository;
    @Mock BookingEventPublisher eventPublisher;

    CancellationService service;

    UUID userId    = UUID.randomUUID();
    UUID slotId    = UUID.randomUUID();
    UUID holdId    = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    UUID venueId   = UUID.randomUUID();
    String confirmationNumber = "SL-20260303-1234";

    @BeforeEach
    void setUp() {
        service = new CancellationService(jdbcTemplate, venueJdbcTemplate, txManager, redisHoldRepository, eventPublisher);

        lenient().when(txManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        lenient().when(jdbcTemplate.update(anyString(), any(String.class))).thenReturn(1);
        lenient().when(jdbcTemplate.update(anyString(), any(UUID.class))).thenReturn(1);
        lenient().when(jdbcTemplate.update(any(PreparedStatementCreator.class))).thenReturn(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void bookingNotFound_emptyResult_throwsBookingNotFoundException() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(String.class)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.cancel(confirmationNumber, userId))
                .isInstanceOf(BookingNotFoundException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void forbidden_differentUserId_throwsForbiddenException() {
        UUID otherUserId = UUID.randomUUID();
        CancellationService.BookingWithSlot booking = confirmedBooking(otherUserId, slotId,
                Instant.now().plus(48, ChronoUnit.HOURS));

        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(String.class)))
                .thenReturn(List.of(booking));

        assertThatThrownBy(() -> service.cancel(confirmationNumber, userId))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void allAlreadyCancelled_convergence_returns200WithNoDbWrites() {
        CancellationService.BookingWithSlot booking = cancelledBooking(userId, slotId);

        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(String.class)))
                .thenReturn(List.of(booking));

        CancelResponse response = service.cancel(confirmationNumber, userId);

        assertThat(response.confirmationNumber()).isEqualTo(confirmationNumber);
        assertThat(response.bookings()).hasSize(1);
        assertThat(response.bookings().get(0).status()).isEqualTo("CANCELLED");

        // No DB writes — convergence exits early
        verify(jdbcTemplate, never()).update(anyString(), eq(confirmationNumber));
    }

    @Test
    @SuppressWarnings("unchecked")
    void withinWindow_exactlyAt24h_throwsCancellationWindowClosed() {
        // exactly 24h from now — isAfter is false, so should throw
        Instant exactlyAt24h = Instant.now().plus(24, ChronoUnit.HOURS);
        CancellationService.BookingWithSlot booking = confirmedBooking(userId, slotId, exactlyAt24h);

        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(String.class)))
                .thenReturn(List.of(booking));

        assertThatThrownBy(() -> service.cancel(confirmationNumber, userId))
                .isInstanceOf(CancellationWindowClosedException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void withinWindow_under24h_throwsCancellationWindowClosed() {
        Instant under24h = Instant.now().plus(23, ChronoUnit.HOURS);
        CancellationService.BookingWithSlot booking = confirmedBooking(userId, slotId, under24h);

        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(String.class)))
                .thenReturn(List.of(booking));

        assertThatThrownBy(() -> service.cancel(confirmationNumber, userId))
                .isInstanceOf(CancellationWindowClosedException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void happyPath_over24h_cancelsAndPublishesEvent() {
        Instant over24h = Instant.now().plus(25, ChronoUnit.HOURS);
        CancellationService.BookingWithSlot booking = confirmedBooking(userId, slotId, over24h);

        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(String.class)))
                .thenReturn(List.of(booking));

        CancelResponse response = service.cancel(confirmationNumber, userId);

        assertThat(response.confirmationNumber()).isEqualTo(confirmationNumber);
        assertThat(response.bookings()).hasSize(1);
        assertThat(response.bookings().get(0).status()).isEqualTo("CANCELLED");
        assertThat(response.cancelledAt()).isNotNull();

        // Redis stale key cleanup must fire
        verify(redisHoldRepository).del(slotId);

        // Event published
        ArgumentCaptor<BookingCancelledEvent> captor = ArgumentCaptor.forClass(BookingCancelledEvent.class);
        verify(eventPublisher).publishBookingCancelled(captor.capture());
        assertThat(captor.getValue().confirmationNumber()).isEqualTo(confirmationNumber);
        assertThat(captor.getValue().cancelledSlotIds()).containsExactly(slotId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void nullStartTime_treatedAsWindowClosed_throwsCancellationWindowClosedException() {
        // A confirmed booking whose slot has no startTime — treated conservatively as
        // window closed rather than allowing an unverifiable cancellation.
        CancellationService.BookingWithSlot booking = new CancellationService.BookingWithSlot(
                UUID.randomUUID(), sessionId, confirmationNumber,
                userId, slotId, holdId, "CONFIRMED",
                Instant.now().minusSeconds(3600), null,
                null, venueId);

        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(String.class)))
                .thenReturn(List.of(booking));

        assertThatThrownBy(() -> service.cancel(confirmationNumber, userId))
                .isInstanceOf(CancellationWindowClosedException.class);
    }

    // --- helpers ---

    private CancellationService.BookingWithSlot confirmedBooking(UUID userId, UUID slotId, Instant startTime) {
        return new CancellationService.BookingWithSlot(
                UUID.randomUUID(), sessionId, confirmationNumber,
                userId, slotId, holdId, "CONFIRMED",
                Instant.now().minusSeconds(3600), null,
                startTime, venueId);
    }

    private CancellationService.BookingWithSlot cancelledBooking(UUID userId, UUID slotId) {
        return new CancellationService.BookingWithSlot(
                UUID.randomUUID(), sessionId, confirmationNumber,
                userId, slotId, holdId, "CANCELLED",
                Instant.now().minusSeconds(7200),
                Instant.now().minusSeconds(3600),
                Instant.now().plus(48, ChronoUnit.HOURS), venueId);
    }
}
