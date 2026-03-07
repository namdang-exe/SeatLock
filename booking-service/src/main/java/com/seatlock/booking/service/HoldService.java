package com.seatlock.booking.service;

import com.seatlock.booking.client.InternalSlotResponse;
import com.seatlock.booking.client.SlotVerificationClient;
import com.seatlock.booking.domain.Hold;
import com.seatlock.booking.domain.HoldStatus;
import com.seatlock.booking.dto.HoldResponse;
import com.seatlock.booking.dto.HoldResponse.HoldItemResponse;
import com.seatlock.booking.exception.RedisUnavailableException;
import com.seatlock.booking.exception.SlotNotAvailableException;
import com.seatlock.booking.redis.HoldPayload;
import org.springframework.dao.DataAccessException;
import com.seatlock.booking.redis.RedisHoldRepository;
import com.seatlock.booking.repository.HoldRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class HoldService {

    static final int HOLD_DURATION_MINUTES = 30;

    private final HoldRepository holdRepository;
    private final RedisHoldRepository redisHoldRepository;
    private final SlotVerificationClient slotVerificationClient;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public HoldService(HoldRepository holdRepository,
                       RedisHoldRepository redisHoldRepository,
                       SlotVerificationClient slotVerificationClient,
                       JdbcTemplate jdbcTemplate,
                       PlatformTransactionManager txManager) {
        this.holdRepository = holdRepository;
        this.redisHoldRepository = redisHoldRepository;
        this.slotVerificationClient = slotVerificationClient;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(txManager);
    }

    /**
     * Creates holds for the given slots, all-or-nothing.
     * Follows the exact 8-step operation sequence from the design spec.
     */
    public HoldResponse createHold(UUID userId, List<UUID> slotIds, UUID sessionId) {

        // Step 2: Idempotency check — return existing ACTIVE holds if this session already ran
        List<Hold> existing = holdRepository.findBySessionIdAndStatus(sessionId, HoldStatus.ACTIVE);
        if (!existing.isEmpty()) {
            return toResponse(sessionId, existing.get(0).getExpiresAt(), existing);
        }

        // Step 3: Verify all slots exist via venue-service (throws SlotNotFoundException if any missing)
        List<InternalSlotResponse> verifiedSlots = slotVerificationClient.verify(slotIds);

        // Step 4: Generate IDs
        Instant expiresAt = Instant.now().plus(HOLD_DURATION_MINUTES, ChronoUnit.MINUTES);
        List<Hold> holds = slotIds.stream().map(slotId -> {
            Hold h = new Hold();
            h.setSessionId(sessionId);
            h.setUserId(userId);
            h.setSlotId(slotId);
            h.setExpiresAt(expiresAt);
            return h;
        }).toList();

        // Step 5: Redis SETNX phase — all-or-nothing
        List<UUID> setSlotIds = new ArrayList<>();
        List<UUID> unavailableSlotIds = new ArrayList<>();

        try {
            for (Hold hold : holds) {
                HoldPayload payload = new HoldPayload(hold.getHoldId(), userId, sessionId, expiresAt);
                boolean acquired = redisHoldRepository.setnx(hold.getSlotId(), payload);
                if (acquired) {
                    setSlotIds.add(hold.getSlotId());
                } else {
                    unavailableSlotIds.add(hold.getSlotId());
                }
            }
        } catch (DataAccessException e) {
            // Redis is unreachable (after retries). Clean up any keys already set.
            setSlotIds.forEach(redisHoldRepository::del);
            throw new RedisUnavailableException(e);
        }

        if (!unavailableSlotIds.isEmpty()) {
            // Roll back all successfully-SET keys before returning 409
            setSlotIds.forEach(redisHoldRepository::del);
            throw new SlotNotAvailableException(unavailableSlotIds);
        }

        // Step 6: Postgres transaction — INSERT holds + UPDATE slots.status
        try {
            transactionTemplate.execute(status -> {
                holdRepository.saveAll(holds);
                UUID[] slotIdArray = slotIds.toArray(new UUID[0]);
                int updated = jdbcTemplate.update(conn -> {
                    var ps = conn.prepareStatement(
                            "UPDATE slots SET status = 'HELD'" +
                            " WHERE slot_id = ANY(?) AND status = 'AVAILABLE'");
                    ps.setArray(1, conn.createArrayOf("uuid", slotIdArray));
                    return ps;
                });
                if (updated != slotIds.size()) {
                    // ≤60s expiry-lag window: Redis key gone but slot still HELD in Postgres
                    throw new SlotNotAvailableException(slotIds);
                }
                return null;
            });
        } catch (SlotNotAvailableException e) {
            slotIds.forEach(redisHoldRepository::del);
            throw e;
        } catch (Exception e) {
            slotIds.forEach(redisHoldRepository::del);
            throw e;
        }

        // Step 7: Invalidate availability cache — non-fatal
        invalidateSlotCaches(verifiedSlots);

        // Step 8: Return 200
        return toResponse(sessionId, expiresAt, holds);
    }

    private void invalidateSlotCaches(List<InternalSlotResponse> verifiedSlots) {
        verifiedSlots.stream()
                .collect(Collectors.toMap(
                        s -> s.venueId() + ":" +
                             s.startTime().atZone(ZoneOffset.UTC).toLocalDate().toString(),
                        s -> s,
                        (a, b) -> a))   // deduplicate by venue+date
                .forEach((key, slot) ->
                        redisHoldRepository.deleteSlotCache(
                                slot.venueId(),
                                slot.startTime().atZone(ZoneOffset.UTC).toLocalDate().toString()));
    }

    private HoldResponse toResponse(UUID sessionId, Instant expiresAt, List<Hold> holds) {
        List<HoldItemResponse> items = holds.stream()
                .map(h -> new HoldItemResponse(h.getHoldId(), h.getSlotId()))
                .toList();
        return new HoldResponse(sessionId, expiresAt, items);
    }
}
