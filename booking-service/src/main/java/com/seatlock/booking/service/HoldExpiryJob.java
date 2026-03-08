package com.seatlock.booking.service;

import com.seatlock.booking.event.BookingEventPublisher;
import com.seatlock.booking.event.HoldExpiredEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class HoldExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(HoldExpiryJob.class);

    private final JdbcTemplate jdbcTemplate;
    private final JdbcTemplate venueJdbcTemplate;
    private final PlatformTransactionManager txManager;
    private final BookingEventPublisher eventPublisher;
    private final Counter holdsExpiredCounter;
    private final DistributionSummary batchSizeSummary;

    @Value("${seatlock.expiry.batch-size:500}")
    int batchSize;

    @Value("${seatlock.expiry.max-retries:3}")
    int maxRetries;

    @Value("${seatlock.expiry.retry-backoff-base-ms:500}")
    long retryBackoffBaseMs;

    public HoldExpiryJob(JdbcTemplate jdbcTemplate,
                         @Qualifier("venueJdbcTemplate") JdbcTemplate venueJdbcTemplate,
                         PlatformTransactionManager txManager,
                         BookingEventPublisher eventPublisher,
                         MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.venueJdbcTemplate = venueJdbcTemplate;
        this.txManager = txManager;
        this.eventPublisher = eventPublisher;
        this.holdsExpiredCounter = Counter.builder("seatlock.holds.expired")
                .register(meterRegistry);
        this.batchSizeSummary = DistributionSummary.builder("seatlock.expiry.batch.size")
                .register(meterRegistry);
    }

    @Scheduled(
            initialDelayString = "${seatlock.expiry.initial-delay-ms:0}",
            fixedDelayString   = "${seatlock.expiry.interval-ms:60000}")
    public void expireHolds() {
        log.debug("Hold expiry job starting with batch size {}", batchSize);
        processWithRetry(batchSize, 0);
    }

    void processWithRetry(int currentBatchSize, int attempt) {
        try {
            TransactionTemplate tt = new TransactionTemplate(txManager);
            List<ExpiredHoldRow> expired = new ArrayList<>();

            tt.execute(status -> {
                // Step 1: SELECT FOR UPDATE SKIP LOCKED — safe across multiple service instances
                List<ExpiredHoldRow> rows = jdbcTemplate.query(
                        conn -> {
                            var ps = conn.prepareStatement(
                                    "SELECT hold_id, session_id, user_id, slot_id " +
                                    "FROM holds " +
                                    "WHERE status = 'ACTIVE' AND expires_at < NOW() " +
                                    "LIMIT ? " +
                                    "FOR UPDATE SKIP LOCKED");
                            ps.setInt(1, currentBatchSize);
                            return ps;
                        },
                        (rs, rowNum) -> new ExpiredHoldRow(
                                rs.getObject("hold_id", UUID.class),
                                rs.getObject("session_id", UUID.class),
                                rs.getObject("user_id", UUID.class),
                                rs.getObject("slot_id", UUID.class)
                        )
                );

                if (rows.isEmpty()) return null;

                UUID[] holdIds = rows.stream().map(ExpiredHoldRow::holdId).toArray(UUID[]::new);
                UUID[] slotIds = rows.stream().map(ExpiredHoldRow::slotId).toArray(UUID[]::new);

                // Step 2a: Expire holds (AND status='ACTIVE' is belt-and-suspenders)
                jdbcTemplate.update(conn -> {
                    var ps = conn.prepareStatement(
                            "UPDATE holds SET status = 'EXPIRED' " +
                            "WHERE hold_id = ANY(?) AND status = 'ACTIVE'");
                    ps.setArray(1, conn.createArrayOf("uuid", holdIds));
                    return ps;
                });

                // Step 2b: Release slots (AND status='HELD' prevents resetting a BOOKED slot)
                jdbcTemplate.update(conn -> {
                    var ps = conn.prepareStatement(
                            "UPDATE slots SET status = 'AVAILABLE' " +
                            "WHERE slot_id = ANY(?) AND status = 'HELD'");
                    ps.setArray(1, conn.createArrayOf("uuid", slotIds));
                    return ps;
                });

                expired.addAll(rows);
                return null;
            });

            // Step 3: NO REDIS OPERATION — Redis TTL has already fired; keys are gone.

            // Step 3b: Update slot status in venue_db — best-effort (non-fatal)
            if (!expired.isEmpty()) {
                UUID[] expiredSlotIds = expired.stream().map(ExpiredHoldRow::slotId).toArray(UUID[]::new);
                try {
                    venueJdbcTemplate.update(conn -> {
                        var ps = conn.prepareStatement(
                                "UPDATE slots SET status = 'AVAILABLE' " +
                                "WHERE slot_id = ANY(?) AND status = 'HELD'");
                        ps.setArray(1, conn.createArrayOf("uuid", expiredSlotIds));
                        return ps;
                    });
                } catch (Exception e) {
                    log.warn("Failed to update expired slot status in venue_db (non-fatal): {}", e.getMessage());
                }
            }

            // Step 4: Publish one HoldExpiredEvent per session (group slots by sessionId)
            if (!expired.isEmpty()) {
                Map<UUID, List<ExpiredHoldRow>> bySession = expired.stream()
                        .collect(Collectors.groupingBy(ExpiredHoldRow::sessionId));

                for (Map.Entry<UUID, List<ExpiredHoldRow>> entry : bySession.entrySet()) {
                    UUID sessionId = entry.getKey();
                    UUID userId = entry.getValue().get(0).userId();
                    List<UUID> slotIds = entry.getValue().stream()
                            .map(ExpiredHoldRow::slotId).toList();
                    eventPublisher.publishHoldExpired(
                            new HoldExpiredEvent(sessionId, userId, slotIds, Instant.now()));
                }

                holdsExpiredCounter.increment(expired.size());
                batchSizeSummary.record(expired.size());
                log.debug("Hold expiry job processed {} holds across {} sessions",
                        expired.size(), bySession.size());
            }

        } catch (PessimisticLockingFailureException e) {
            if (attempt < maxRetries) {
                long backoffMs = retryBackoffBaseMs * (1L << attempt);
                log.warn("Lock contention on attempt {}, retrying in {}ms", attempt + 1, backoffMs);
                if (backoffMs > 0) {
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                processWithRetry(currentBatchSize, attempt + 1);
            } else {
                int halved = currentBatchSize / 2;
                if (halved > 0) {
                    log.warn("Max retries exhausted, halving batch size from {} to {}", currentBatchSize, halved);
                    processWithRetry(halved, 0);
                } else {
                    log.error("Batch size cannot be halved further ({}); skipping expiry run", currentBatchSize);
                }
            }
        }
    }

    record ExpiredHoldRow(UUID holdId, UUID sessionId, UUID userId, UUID slotId) {}
}
