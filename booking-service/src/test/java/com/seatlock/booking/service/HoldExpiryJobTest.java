package com.seatlock.booking.service;

import com.seatlock.booking.event.BookingEventPublisher;
import com.seatlock.booking.event.HoldExpiredEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HoldExpiryJobTest {

    @Mock JdbcTemplate jdbcTemplate;
    @Mock JdbcTemplate venueJdbcTemplate;
    @Mock PlatformTransactionManager txManager;
    @Mock BookingEventPublisher eventPublisher;

    HoldExpiryJob job;

    UUID holdId    = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    UUID userId    = UUID.randomUUID();
    UUID slotId    = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        job = new HoldExpiryJob(jdbcTemplate, venueJdbcTemplate, txManager, eventPublisher,
                new SimpleMeterRegistry());
        job.batchSize = 100;
        job.maxRetries = 3;
        job.retryBackoffBaseMs = 0; // no sleep in tests

        lenient().when(txManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        lenient().when(jdbcTemplate.update(any(PreparedStatementCreator.class))).thenReturn(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void noExpiredHolds_nothingProcessed() {
        when(jdbcTemplate.query(any(PreparedStatementCreator.class), any(RowMapper.class)))
                .thenReturn(List.of());

        job.processWithRetry(100, 0);

        verify(eventPublisher, never()).publishHoldExpired(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void happyPath_publishesOneEventPerSession() {
        HoldExpiryJob.ExpiredHoldRow row =
                new HoldExpiryJob.ExpiredHoldRow(holdId, sessionId, userId, slotId);
        when(jdbcTemplate.query(any(PreparedStatementCreator.class), any(RowMapper.class)))
                .thenReturn(List.of(row));

        job.processWithRetry(100, 0);

        ArgumentCaptor<HoldExpiredEvent> captor = ArgumentCaptor.forClass(HoldExpiredEvent.class);
        verify(eventPublisher).publishHoldExpired(captor.capture());
        HoldExpiredEvent event = captor.getValue();
        assertThat(event.sessionId()).isEqualTo(sessionId);
        assertThat(event.userId()).isEqualTo(userId);
        assertThat(event.expiredSlotIds()).containsExactly(slotId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void multipleHoldsSameSession_publishedAsOneEvent() {
        UUID slotId2 = UUID.randomUUID();
        HoldExpiryJob.ExpiredHoldRow row1 =
                new HoldExpiryJob.ExpiredHoldRow(UUID.randomUUID(), sessionId, userId, slotId);
        HoldExpiryJob.ExpiredHoldRow row2 =
                new HoldExpiryJob.ExpiredHoldRow(UUID.randomUUID(), sessionId, userId, slotId2);
        when(jdbcTemplate.query(any(PreparedStatementCreator.class), any(RowMapper.class)))
                .thenReturn(List.of(row1, row2));

        job.processWithRetry(100, 0);

        ArgumentCaptor<HoldExpiredEvent> captor = ArgumentCaptor.forClass(HoldExpiredEvent.class);
        verify(eventPublisher, times(1)).publishHoldExpired(captor.capture());
        assertThat(captor.getValue().expiredSlotIds()).containsExactlyInAnyOrder(slotId, slotId2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void lockContention_retriesOnceAndSucceeds() {
        HoldExpiryJob.ExpiredHoldRow row =
                new HoldExpiryJob.ExpiredHoldRow(holdId, sessionId, userId, slotId);
        when(jdbcTemplate.query(any(PreparedStatementCreator.class), any(RowMapper.class)))
                .thenThrow(new PessimisticLockingFailureException("contention"))
                .thenReturn(List.of(row));

        job.processWithRetry(100, 0);

        verify(jdbcTemplate, times(2)).query(any(PreparedStatementCreator.class), any(RowMapper.class));
        verify(eventPublisher).publishHoldExpired(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void maxRetriesExhausted_batchHalved_thenSucceeds() {
        HoldExpiryJob.ExpiredHoldRow row =
                new HoldExpiryJob.ExpiredHoldRow(holdId, sessionId, userId, slotId);
        // 4 failures (attempts 0,1,2,3) → batch halved → success
        when(jdbcTemplate.query(any(PreparedStatementCreator.class), any(RowMapper.class)))
                .thenThrow(new PessimisticLockingFailureException("c"))
                .thenThrow(new PessimisticLockingFailureException("c"))
                .thenThrow(new PessimisticLockingFailureException("c"))
                .thenThrow(new PessimisticLockingFailureException("c"))
                .thenReturn(List.of(row));

        job.processWithRetry(100, 0);

        // 4 throws + 1 success on halved batch
        verify(jdbcTemplate, times(5)).query(any(PreparedStatementCreator.class), any(RowMapper.class));
        verify(eventPublisher).publishHoldExpired(any());
    }
}
