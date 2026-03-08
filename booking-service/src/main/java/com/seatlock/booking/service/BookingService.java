package com.seatlock.booking.service;

import com.seatlock.booking.client.InternalSlotResponse;
import com.seatlock.booking.client.SlotVerificationClient;
import com.seatlock.booking.domain.Booking;
import com.seatlock.booking.domain.BookingStatus;
import com.seatlock.booking.domain.Hold;
import com.seatlock.booking.domain.HoldStatus;
import com.seatlock.booking.dto.BookingResponse;
import com.seatlock.booking.dto.BookingResponse.BookingItemResponse;
import com.seatlock.booking.event.BookingConfirmedEvent;
import com.seatlock.booking.event.BookingEventPublisher;
import com.seatlock.booking.exception.ForbiddenException;
import com.seatlock.booking.exception.HoldExpiredException;
import com.seatlock.booking.exception.HoldMismatchException;
import com.seatlock.booking.exception.SessionNotFoundException;
import com.seatlock.booking.redis.HoldPayload;
import com.seatlock.booking.redis.RedisHoldRepository;
import com.seatlock.booking.repository.BookingRepository;
import com.seatlock.booking.repository.HoldRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final BookingRepository bookingRepository;
    private final HoldRepository holdRepository;
    private final RedisHoldRepository redisHoldRepository;
    private final SlotVerificationClient slotVerificationClient;
    private final JdbcTemplate jdbcTemplate;
    private final JdbcTemplate venueJdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ConfirmationNumberGenerator confirmationNumberGenerator;
    private final BookingEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;

    public BookingService(BookingRepository bookingRepository,
                          HoldRepository holdRepository,
                          RedisHoldRepository redisHoldRepository,
                          SlotVerificationClient slotVerificationClient,
                          JdbcTemplate jdbcTemplate,
                          @Qualifier("venueJdbcTemplate") JdbcTemplate venueJdbcTemplate,
                          PlatformTransactionManager txManager,
                          ConfirmationNumberGenerator confirmationNumberGenerator,
                          BookingEventPublisher eventPublisher,
                          MeterRegistry meterRegistry) {
        this.bookingRepository = bookingRepository;
        this.holdRepository = holdRepository;
        this.redisHoldRepository = redisHoldRepository;
        this.slotVerificationClient = slotVerificationClient;
        this.jdbcTemplate = jdbcTemplate;
        this.venueJdbcTemplate = venueJdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(txManager);
        this.confirmationNumberGenerator = confirmationNumberGenerator;
        this.eventPublisher = eventPublisher;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Confirms a booking session. Follows the exact 8-step operation sequence.
     * CRITICAL: Postgres commits BEFORE Redis DEL (ADR-008).
     */
    public BookingResponse confirmBooking(UUID sessionId, UUID userId) {

        // Step 0: Idempotency check — return existing CONFIRMED bookings if already done
        List<Booking> existing = bookingRepository.findBySessionIdAndStatus(sessionId, BookingStatus.CONFIRMED);
        if (!existing.isEmpty()) {
            return toResponse(existing.get(0).getConfirmationNumber(), sessionId, existing);
        }

        // Step 1: Load active holds — 404 if none
        List<Hold> holds = holdRepository.findBySessionIdAndStatus(sessionId, HoldStatus.ACTIVE);
        if (holds.isEmpty()) {
            throw new SessionNotFoundException(sessionId);
        }

        // Step 2: Authorize — all holds must belong to the requesting user
        for (Hold hold : holds) {
            if (!hold.getUserId().equals(userId)) {
                throw new ForbiddenException();
            }
        }

        List<UUID> slotIds = holds.stream().map(Hold::getSlotId).toList();

        // Fetch venueIds for cache invalidation (best-effort; failure is non-fatal)
        List<InternalSlotResponse> verifiedSlots;
        try {
            verifiedSlots = slotVerificationClient.verify(slotIds);
        } catch (Exception ignored) {
            verifiedSlots = List.of();
        }

        // Step 3: Verify Redis hold keys for each slot
        for (Hold hold : holds) {
            Optional<HoldPayload> payload = redisHoldRepository.getHold(hold.getSlotId());
            if (payload.isEmpty()) {
                throw new HoldExpiredException();
            }
            if (!payload.get().holdId().equals(hold.getHoldId())) {
                throw new HoldMismatchException();
            }
        }

        // Step 4: Generate confirmation number (same for all bookings in this session)
        String confirmationNumber = confirmationNumberGenerator.generate();

        List<Booking> bookings = holds.stream().map(hold -> {
            Booking b = new Booking();
            b.setSessionId(sessionId);
            b.setConfirmationNumber(confirmationNumber);
            b.setUserId(userId);
            b.setSlotId(hold.getSlotId());
            b.setHoldId(hold.getHoldId());
            return b;
        }).toList();

        // Step 5: Postgres transaction — COMMITS FIRST (crash-safe order per ADR-008)
        UUID[] slotIdArray = slotIds.toArray(new UUID[0]);
        transactionTemplate.execute(status -> {
            bookingRepository.saveAll(bookings);
            holdRepository.updateStatusBySessionId(sessionId, HoldStatus.ACTIVE, HoldStatus.CONFIRMED);
            jdbcTemplate.update(conn -> {
                var ps = conn.prepareStatement(
                        "UPDATE slots SET status = 'BOOKED' WHERE slot_id = ANY(?)");
                ps.setArray(1, conn.createArrayOf("uuid", slotIdArray));
                return ps;
            });
            return null;
        });

        // Step 6: Update slot status in venue_db — best-effort (non-fatal)
        updateSlotStatusInVenueDb("BOOKED", slotIds);

        // Step 7: Redis cleanup — AFTER Postgres commit (best-effort)
        slotIds.forEach(redisHoldRepository::del);
        invalidateSlotCaches(verifiedSlots);

        // Step 8: Publish event (async, non-blocking stub — SQS wired in Stage 11)
        try {
            eventPublisher.publishBookingConfirmed(
                    new BookingConfirmedEvent(confirmationNumber, sessionId, userId, slotIds, Instant.now()));
        } catch (Exception ignored) {
            // Event publishing failure must not affect booking confirmation response
        }

        // Step 9: Record metric
        Counter.builder("seatlock.bookings.confirmed")
                .register(meterRegistry)
                .increment();

        // Step 10: Return 201
        return toResponse(confirmationNumber, sessionId, bookings);
    }

    private void updateSlotStatusInVenueDb(String newStatus, List<UUID> slotIds) {
        try {
            UUID[] arr = slotIds.toArray(new UUID[0]);
            venueJdbcTemplate.update(conn -> {
                var ps = conn.prepareStatement(
                        "UPDATE slots SET status = ? WHERE slot_id = ANY(?)");
                ps.setString(1, newStatus);
                ps.setArray(2, conn.createArrayOf("uuid", arr));
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
                        (a, b) -> a))
                .forEach((key, slot) ->
                        redisHoldRepository.deleteSlotCache(
                                slot.venueId(),
                                slot.startTime().atZone(ZoneOffset.UTC).toLocalDate().toString()));
    }

    private BookingResponse toResponse(String confirmationNumber, UUID sessionId, List<Booking> bookings) {
        List<BookingItemResponse> items = bookings.stream()
                .map(b -> new BookingItemResponse(b.getBookingId(), b.getSlotId(), b.getStatus().name()))
                .toList();
        return new BookingResponse(confirmationNumber, sessionId, items);
    }
}
