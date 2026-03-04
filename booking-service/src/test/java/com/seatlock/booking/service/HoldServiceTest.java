package com.seatlock.booking.service;

import com.seatlock.booking.client.InternalSlotResponse;
import com.seatlock.booking.client.SlotVerificationClient;
import com.seatlock.booking.domain.Hold;
import com.seatlock.booking.domain.HoldStatus;
import com.seatlock.booking.dto.HoldResponse;
import com.seatlock.booking.exception.SlotNotAvailableException;
import com.seatlock.booking.redis.HoldPayload;
import com.seatlock.booking.redis.RedisHoldRepository;
import com.seatlock.booking.repository.HoldRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HoldServiceTest {

    @Mock HoldRepository holdRepository;
    @Mock RedisHoldRepository redisHoldRepository;
    @Mock SlotVerificationClient slotVerificationClient;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock PlatformTransactionManager txManager;

    HoldService holdService;

    UUID userId  = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    UUID slotId1 = UUID.randomUUID();
    UUID slotId2 = UUID.randomUUID();
    UUID venueId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        holdService = new HoldService(holdRepository, redisHoldRepository,
                slotVerificationClient, jdbcTemplate, txManager);

        // Default: txManager provides a usable TransactionStatus so the callback executes
        lenient().when(txManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());

        // Default: JDBC update succeeds with correct row count (overridden in specific tests)
        lenient().when(jdbcTemplate.update(any(PreparedStatementCreator.class)))
                .thenReturn(2);
    }

    @Test
    void idempotencyCheck_existingHolds_returnsExistingHoldsWithoutCallingVenueService() {
        Hold h1 = holdWithSlot(slotId1, sessionId, userId);
        Hold h2 = holdWithSlot(slotId2, sessionId, userId);
        when(holdRepository.findBySessionIdAndStatus(sessionId, HoldStatus.ACTIVE))
                .thenReturn(List.of(h1, h2));

        HoldResponse response = holdService.createHold(userId, List.of(slotId1, slotId2), sessionId);

        assertThat(response.sessionId()).isEqualTo(sessionId);
        assertThat(response.holds()).hasSize(2);
        verify(slotVerificationClient, never()).verify(anyList());
        verify(redisHoldRepository, never()).setnx(any(), any());
    }

    @Test
    void setnxFailure_secondSlot_deletesFirstKeyAndThrows() {
        when(holdRepository.findBySessionIdAndStatus(sessionId, HoldStatus.ACTIVE))
                .thenReturn(List.of());
        when(slotVerificationClient.verify(anyList()))
                .thenReturn(List.of(
                        slotResponse(slotId1, venueId),
                        slotResponse(slotId2, venueId)));

        // slotId1 succeeds, slotId2 fails
        when(redisHoldRepository.setnx(eq(slotId1), any(HoldPayload.class))).thenReturn(true);
        when(redisHoldRepository.setnx(eq(slotId2), any(HoldPayload.class))).thenReturn(false);

        assertThatThrownBy(() -> holdService.createHold(userId, List.of(slotId1, slotId2), sessionId))
                .isInstanceOf(SlotNotAvailableException.class)
                .satisfies(e -> {
                    var ex = (SlotNotAvailableException) e;
                    assertThat(ex.getUnavailableSlotIds()).containsExactly(slotId2);
                });

        // slotId1 was successfully SET, so it must be cleaned up
        verify(redisHoldRepository).del(slotId1);
        // slotId2 was never SET, so no DEL for it from rollback phase
        verify(redisHoldRepository, never()).del(slotId2);
    }

    @Test
    void postgresRowCountMismatch_deletesAllRedisKeysAndThrows() {
        when(holdRepository.findBySessionIdAndStatus(sessionId, HoldStatus.ACTIVE))
                .thenReturn(List.of());
        when(slotVerificationClient.verify(anyList()))
                .thenReturn(List.of(
                        slotResponse(slotId1, venueId),
                        slotResponse(slotId2, venueId)));
        when(redisHoldRepository.setnx(any(UUID.class), any(HoldPayload.class))).thenReturn(true);
        // Postgres UPDATE affects only 1 row instead of 2
        when(jdbcTemplate.update(any(PreparedStatementCreator.class))).thenReturn(1);

        assertThatThrownBy(() -> holdService.createHold(userId, List.of(slotId1, slotId2), sessionId))
                .isInstanceOf(SlotNotAvailableException.class);

        // Both Redis keys must be cleaned up
        verify(redisHoldRepository).del(slotId1);
        verify(redisHoldRepository).del(slotId2);
    }

    @Test
    void happyPath_returnsResponseWithCorrectSessionAndHolds() {
        when(holdRepository.findBySessionIdAndStatus(sessionId, HoldStatus.ACTIVE))
                .thenReturn(List.of());
        when(slotVerificationClient.verify(anyList()))
                .thenReturn(List.of(
                        slotResponse(slotId1, venueId),
                        slotResponse(slotId2, venueId)));
        when(redisHoldRepository.setnx(any(UUID.class), any(HoldPayload.class))).thenReturn(true);
        when(holdRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        HoldResponse response = holdService.createHold(userId, List.of(slotId1, slotId2), sessionId);

        assertThat(response.sessionId()).isEqualTo(sessionId);
        assertThat(response.expiresAt()).isAfter(Instant.now());
        assertThat(response.holds()).hasSize(2)
                .extracting(HoldResponse.HoldItemResponse::slotId)
                .containsExactlyInAnyOrder(slotId1, slotId2);
    }

    // --- helpers ---

    private Hold holdWithSlot(UUID slotId, UUID sessionId, UUID userId) {
        Hold h = new Hold();
        h.setSlotId(slotId);
        h.setSessionId(sessionId);
        h.setUserId(userId);
        h.setExpiresAt(Instant.now().plusSeconds(1800));
        return h;
    }

    private InternalSlotResponse slotResponse(UUID slotId, UUID venueId) {
        return new InternalSlotResponse(slotId, venueId, Instant.now().plusSeconds(3600), "AVAILABLE");
    }
}
