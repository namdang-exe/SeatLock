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
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import com.seatlock.booking.redis.RedisHoldRepository;
import com.seatlock.booking.repository.HoldRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(HoldService.class);

    static final int HOLD_DURATION_MINUTES = 30;

    private final HoldRepository holdRepository;
    private final RedisHoldRepository redisHoldRepository;
    private final SlotVerificationClient slotVerificationClient;
    private final JdbcTemplate jdbcTemplate;
    private final JdbcTemplate venueJdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final MeterRegistry meterRegistry;

    public HoldService(HoldRepository holdRepository,
                       RedisHoldRepository redisHoldRepository,
                       SlotVerificationClient slotVerificationClient,
                       JdbcTemplate jdbcTemplate,
                       @Qualifier("venueJdbcTemplate") JdbcTemplate venueJdbcTemplate,
                       PlatformTransactionManager txManager,
                       MeterRegistry meterRegistry) {
        this.holdRepository = holdRepository;
        this.redisHoldRepository = redisHoldRepository;
        this.slotVerificationClient = slotVerificationClient;
        this.jdbcTemplate = jdbcTemplate;
        this.venueJdbcTemplate = venueJdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(txManager);
        this.meterRegistry = meterRegistry;
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

        // Step 6: Postgres transaction — upsert slot/venue metadata, INSERT holds, UPDATE slots.status
        try {
            transactionTemplate.execute(status -> {
                // Enforce cross-service referential integrity at the application layer:
                // write-through slot and venue data fetched from venue-service into local tables.
                for (InternalSlotResponse slot : verifiedSlots) {
                    if (slot.venueId() != null) {
                        jdbcTemplate.update(
                                "INSERT INTO venues (venue_id, name) VALUES (?, ?) " +
                                "ON CONFLICT (venue_id) DO UPDATE SET name = EXCLUDED.name",
                                slot.venueId(), slot.venueName());
                    }
                    java.sql.Timestamp startTime = slot.startTime() != null
                            ? java.sql.Timestamp.from(slot.startTime()) : null;
                    jdbcTemplate.update(
                            "INSERT INTO slots (slot_id, venue_id, status, start_time) VALUES (?, ?, 'AVAILABLE', ?) " +
                            "ON CONFLICT (slot_id) DO UPDATE SET venue_id = EXCLUDED.venue_id, start_time = EXCLUDED.start_time",
                            slot.slotId(), slot.venueId(), startTime);
                }
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

        // Step 7: Update slot status in venue_db — best-effort (non-fatal)
        updateSlotStatusInVenueDb("HELD", "AVAILABLE", slotIds);

        // Step 8: Invalidate availability cache — non-fatal
        invalidateSlotCaches(verifiedSlots);

        // Step 9: Record metrics — one increment per hold (per slot), tagged by venueId
        for (InternalSlotResponse slot : verifiedSlots) {
            String venueTag = slot.venueId() != null ? slot.venueId().toString() : "unknown";
            Counter.builder("seatlock.holds.created")
                    .tag("venueId", venueTag)
                    .register(meterRegistry)
                    .increment();
        }

        // Step 10: Return 200
        return toResponse(sessionId, expiresAt, holds);
    }

    private void updateSlotStatusInVenueDb(String newStatus, String prevStatus, List<UUID> slotIds) {
        try {
            UUID[] arr = slotIds.toArray(new UUID[0]);
            venueJdbcTemplate.update(conn -> {
                var ps = conn.prepareStatement(
                        "UPDATE slots SET status = ? WHERE slot_id = ANY(?) AND status = ?");
                ps.setString(1, newStatus);
                ps.setArray(2, conn.createArrayOf("uuid", arr));
                ps.setString(3, prevStatus);
                return ps;
            });
        } catch (Exception e) {
            log.warn("Failed to update slot status to {} in venue_db (non-fatal): {}", newStatus, e.getMessage());
        }
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
