package com.seatlock.booking.service;

import com.seatlock.booking.dto.AdminBookingResponse;
import com.seatlock.booking.dto.AdminBookingResponse.AdminBookingItem;
import com.seatlock.booking.dto.BookingHistoryResponse;
import com.seatlock.booking.dto.BookingHistoryResponse.BookingSession;
import com.seatlock.booking.dto.BookingHistoryResponse.BookingSession.BookingSlot;
import com.seatlock.booking.dto.CancelResponse;
import com.seatlock.booking.dto.CancelResponse.CancelledBookingItem;
import com.seatlock.booking.event.BookingCancelledEvent;
import com.seatlock.booking.event.BookingEventPublisher;
import com.seatlock.booking.exception.BookingNotFoundException;
import com.seatlock.booking.exception.CancellationWindowClosedException;
import com.seatlock.booking.exception.ForbiddenException;
import com.seatlock.booking.redis.RedisHoldRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class CancellationService {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final RedisHoldRepository redisHoldRepository;
    private final BookingEventPublisher eventPublisher;

    /**
     * Internal projection of a booking joined with its slot data.
     * Used only within this service for cancellation logic.
     */
    record BookingWithSlot(
            UUID bookingId,
            UUID sessionId,
            String confirmationNumber,
            UUID userId,
            UUID slotId,
            UUID holdId,
            String status,
            Instant createdAt,
            Instant cancelledAt,
            Instant startTime,
            UUID venueId
    ) {}

    private static final RowMapper<BookingWithSlot> BOOKING_WITH_SLOT_MAPPER = (rs, i) -> new BookingWithSlot(
            (UUID) rs.getObject("booking_id"),
            (UUID) rs.getObject("session_id"),
            rs.getString("confirmation_number"),
            (UUID) rs.getObject("user_id"),
            (UUID) rs.getObject("slot_id"),
            (UUID) rs.getObject("hold_id"),
            rs.getString("status"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("cancelled_at")),
            toInstant(rs.getTimestamp("start_time")),
            (UUID) rs.getObject("venue_id")
    );

    public CancellationService(JdbcTemplate jdbcTemplate,
                               PlatformTransactionManager txManager,
                               RedisHoldRepository redisHoldRepository,
                               BookingEventPublisher eventPublisher) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(txManager);
        this.redisHoldRepository = redisHoldRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Cancels a booking session. Follows the exact 8-step operation sequence.
     * Includes ADR-008 stale Redis key cleanup.
     */
    public CancelResponse cancel(String confirmationNumber, UUID userId) {

        // Step 1: Load all bookings + slot data — no status filter
        List<BookingWithSlot> all = loadForCancellation(confirmationNumber);
        if (all.isEmpty()) {
            throw new BookingNotFoundException(confirmationNumber);
        }

        // Step 2: Authorize — every booking must belong to the requesting user
        for (BookingWithSlot b : all) {
            if (!b.userId().equals(userId)) {
                throw new ForbiddenException();
            }
        }

        // Step 3: Convergence — if no CONFIRMED bookings, already fully cancelled
        List<BookingWithSlot> confirmed = all.stream()
                .filter(b -> "CONFIRMED".equals(b.status()))
                .toList();
        if (confirmed.isEmpty()) {
            Instant latestCancelledAt = all.stream()
                    .map(BookingWithSlot::cancelledAt)
                    .filter(t -> t != null)
                    .max(Comparator.naturalOrder())
                    .orElse(Instant.now());
            return toCancelResponse(confirmationNumber, latestCancelledAt, all);
        }

        // Step 4: 24h cancellation window check (CONFIRMED items only)
        Instant cutoff = Instant.now().plus(24, ChronoUnit.HOURS);
        for (BookingWithSlot b : confirmed) {
            if (b.startTime() == null || !b.startTime().isAfter(cutoff)) {
                throw new CancellationWindowClosedException();
            }
        }

        // Step 5: Postgres transaction — bookings → CANCELLED, holds → RELEASED, slots → AVAILABLE
        UUID sessionId = confirmed.get(0).sessionId();
        List<UUID> confirmedSlotIds = confirmed.stream().map(BookingWithSlot::slotId).toList();
        UUID[] slotIdArray = confirmedSlotIds.toArray(new UUID[0]);

        Instant cancelTime = Instant.now();
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                    "UPDATE bookings SET status='CANCELLED', cancelled_at=now() " +
                    "WHERE confirmation_number=? AND status='CONFIRMED'",
                    confirmationNumber);
            jdbcTemplate.update(
                    "UPDATE holds SET status='RELEASED' WHERE session_id=? AND status='CONFIRMED'",
                    sessionId);
            jdbcTemplate.update(conn -> {
                var ps = conn.prepareStatement(
                        "UPDATE slots SET status='AVAILABLE' WHERE slot_id = ANY(?) AND status='BOOKED'");
                ps.setArray(1, conn.createArrayOf("uuid", slotIdArray));
                return ps;
            });
            return null;
        });

        // Step 6: Redis cleanup — DEL hold:{slotId} (ADR-008 stale key protection)
        confirmedSlotIds.forEach(redisHoldRepository::del);
        // Also invalidate the slot availability cache for each affected venue+date
        confirmed.stream()
                .filter(b -> b.venueId() != null && b.startTime() != null)
                .collect(Collectors.toMap(
                        b -> b.venueId().toString() + ":" +
                             b.startTime().atZone(ZoneOffset.UTC).toLocalDate().toString(),
                        b -> b,
                        (a, b) -> a))
                .forEach((key, b) -> redisHoldRepository.deleteSlotCache(
                        b.venueId(),
                        b.startTime().atZone(ZoneOffset.UTC).toLocalDate().toString()));

        // Step 7: Publish BookingCancelledEvent (no-op stub until Stage 11)
        try {
            eventPublisher.publishBookingCancelled(
                    new BookingCancelledEvent(confirmationNumber, sessionId, userId,
                            confirmedSlotIds, Instant.now()));
        } catch (Exception ignored) {
            // Event publishing must not affect cancel response
        }

        // Step 8: Return 200 — build response from in-memory data, reflecting new CANCELLED state
        List<BookingWithSlot> updated = all.stream()
                .map(b -> "CONFIRMED".equals(b.status())
                        ? new BookingWithSlot(b.bookingId(), b.sessionId(), b.confirmationNumber(),
                                b.userId(), b.slotId(), b.holdId(), "CANCELLED",
                                b.createdAt(), cancelTime, b.startTime(), b.venueId())
                        : b)
                .toList();
        return toCancelResponse(confirmationNumber, cancelTime, updated);
    }

    /**
     * Returns the authenticated user's booking history, grouped by confirmationNumber,
     * ordered newest first.
     */
    public BookingHistoryResponse getHistory(UUID userId) {
        List<HistoryRow> rows = jdbcTemplate.query(
                "SELECT b.booking_id, b.session_id, b.confirmation_number, " +
                "       b.slot_id, b.status, b.created_at, b.cancelled_at, " +
                "       s.start_time, v.name AS venue_name " +
                "FROM bookings b " +
                "JOIN slots s ON b.slot_id = s.slot_id " +
                "LEFT JOIN venues v ON s.venue_id = v.venue_id " +
                "WHERE b.user_id = ? " +
                "ORDER BY b.created_at DESC, b.confirmation_number, b.booking_id",
                (rs, i) -> new HistoryRow(
                        (UUID) rs.getObject("booking_id"),
                        (UUID) rs.getObject("session_id"),
                        rs.getString("confirmation_number"),
                        (UUID) rs.getObject("slot_id"),
                        rs.getString("status"),
                        toInstant(rs.getTimestamp("created_at")),
                        toInstant(rs.getTimestamp("cancelled_at")),
                        toInstant(rs.getTimestamp("start_time")),
                        rs.getString("venue_name")),
                userId);

        // Group by confirmationNumber — LinkedHashMap preserves insertion order (newest first)
        Map<String, List<HistoryRow>> grouped = new LinkedHashMap<>();
        for (HistoryRow row : rows) {
            grouped.computeIfAbsent(row.confirmationNumber(), k -> new ArrayList<>()).add(row);
        }

        List<BookingSession> sessions = grouped.entrySet().stream()
                .map(e -> {
                    List<HistoryRow> bookings = e.getValue();
                    HistoryRow first = bookings.get(0);

                    List<BookingSlot> slots = bookings.stream()
                            .map(r -> new BookingSlot(
                                    r.bookingId(), r.slotId(), r.startTime(),
                                    r.venueName(), r.status(), r.cancelledAt()))
                            .toList();

                    return new BookingSession(e.getKey(), first.sessionId(), first.createdAt(), slots);
                })
                .toList();

        return new BookingHistoryResponse(sessions);
    }

    /**
     * Returns confirmed bookings for a venue (admin view). Optional date filter (UTC).
     */
    public AdminBookingResponse getAdminBookings(UUID venueId, LocalDate date) {
        String sql;
        Object[] args;

        if (date == null) {
            sql = "SELECT b.booking_id, b.session_id, b.confirmation_number, b.user_id, " +
                  "       b.slot_id, b.status, b.created_at, s.start_time " +
                  "FROM bookings b " +
                  "JOIN slots s ON b.slot_id = s.slot_id " +
                  "WHERE s.venue_id = ? AND b.status = 'CONFIRMED' " +
                  "ORDER BY s.start_time ASC";
            args = new Object[]{venueId};
        } else {
            sql = "SELECT b.booking_id, b.session_id, b.confirmation_number, b.user_id, " +
                  "       b.slot_id, b.status, b.created_at, s.start_time " +
                  "FROM bookings b " +
                  "JOIN slots s ON b.slot_id = s.slot_id " +
                  "WHERE s.venue_id = ? AND b.status = 'CONFIRMED' " +
                  "  AND DATE(s.start_time AT TIME ZONE 'UTC') = ? " +
                  "ORDER BY s.start_time ASC";
            args = new Object[]{venueId, java.sql.Date.valueOf(date)};
        }

        List<AdminBookingItem> items = jdbcTemplate.query(sql,
                (rs, i) -> new AdminBookingItem(
                        (UUID) rs.getObject("booking_id"),
                        (UUID) rs.getObject("session_id"),
                        rs.getString("confirmation_number"),
                        (UUID) rs.getObject("user_id"),
                        (UUID) rs.getObject("slot_id"),
                        rs.getString("status"),
                        toInstant(rs.getTimestamp("created_at")),
                        toInstant(rs.getTimestamp("start_time"))),
                args);

        return new AdminBookingResponse(items);
    }

    // --- private helpers ---

    private List<BookingWithSlot> loadForCancellation(String confirmationNumber) {
        return jdbcTemplate.query(
                "SELECT b.booking_id, b.session_id, b.confirmation_number, b.user_id, " +
                "       b.slot_id, b.hold_id, b.status, b.created_at, b.cancelled_at, " +
                "       s.start_time, s.venue_id " +
                "FROM bookings b " +
                "JOIN slots s ON b.slot_id = s.slot_id " +
                "WHERE b.confirmation_number = ?",
                BOOKING_WITH_SLOT_MAPPER,
                confirmationNumber);
    }

    private CancelResponse toCancelResponse(String confirmationNumber, Instant cancelledAt,
                                            List<BookingWithSlot> bookings) {
        List<CancelledBookingItem> items = bookings.stream()
                .map(b -> new CancelledBookingItem(b.bookingId(), b.slotId(), b.status()))
                .toList();
        return new CancelResponse(confirmationNumber, cancelledAt, items);
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private record HistoryRow(
            UUID bookingId,
            UUID sessionId,
            String confirmationNumber,
            UUID slotId,
            String status,
            Instant createdAt,
            Instant cancelledAt,
            Instant startTime,
            String venueName
    ) {}
}
