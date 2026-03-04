package com.seatlock.booking.service;

import com.seatlock.booking.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HoldExpiryJobIT extends AbstractIntegrationTest {

    @Autowired HoldExpiryJob holdExpiryJob;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM bookings");
        jdbcTemplate.execute("DELETE FROM holds");
        jdbcTemplate.execute("DELETE FROM slots");
        jdbcTemplate.execute("DELETE FROM users");
    }

    @Test
    void expiredActiveHold_markedExpiredAndSlotReleased() {
        UUID userId    = UUID.randomUUID();
        UUID slotId    = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID holdId    = UUID.randomUUID();

        jdbcTemplate.update("INSERT INTO users (user_id) VALUES (?)", userId);
        jdbcTemplate.update("INSERT INTO slots (slot_id, status) VALUES (?, 'HELD')", slotId);
        jdbcTemplate.update(
                "INSERT INTO holds (hold_id, session_id, user_id, slot_id, expires_at, status) " +
                "VALUES (?, ?, ?, ?, ?, 'ACTIVE')",
                holdId, sessionId, userId, slotId,
                Timestamp.from(Instant.now().minusSeconds(60)));

        holdExpiryJob.expireHolds();

        String holdStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM holds WHERE hold_id = ?", String.class, holdId);
        assertThat(holdStatus).isEqualTo("EXPIRED");

        String slotStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM slots WHERE slot_id = ?", String.class, slotId);
        assertThat(slotStatus).isEqualTo("AVAILABLE");
    }

    @Test
    void notYetExpiredActiveHold_leftAlone() {
        UUID userId    = UUID.randomUUID();
        UUID slotId    = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID holdId    = UUID.randomUUID();

        jdbcTemplate.update("INSERT INTO users (user_id) VALUES (?)", userId);
        jdbcTemplate.update("INSERT INTO slots (slot_id, status) VALUES (?, 'HELD')", slotId);
        jdbcTemplate.update(
                "INSERT INTO holds (hold_id, session_id, user_id, slot_id, expires_at, status) " +
                "VALUES (?, ?, ?, ?, ?, 'ACTIVE')",
                holdId, sessionId, userId, slotId,
                Timestamp.from(Instant.now().plusSeconds(1800)));

        holdExpiryJob.expireHolds();

        String holdStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM holds WHERE hold_id = ?", String.class, holdId);
        assertThat(holdStatus).isEqualTo("ACTIVE");

        String slotStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM slots WHERE slot_id = ?", String.class, slotId);
        assertThat(slotStatus).isEqualTo("HELD");
    }

    @Test
    void concurrentExpiry_skipLockedPreventsDoubleExpiry() throws InterruptedException {
        // Insert 10 expired holds (spec requirement)
        int holdCount = 10;
        for (int i = 0; i < holdCount; i++) {
            UUID userId    = UUID.randomUUID();
            UUID slotId    = UUID.randomUUID();
            UUID sessionId = UUID.randomUUID();
            UUID holdId    = UUID.randomUUID();

            jdbcTemplate.update("INSERT INTO users (user_id) VALUES (?)", userId);
            jdbcTemplate.update("INSERT INTO slots (slot_id, status) VALUES (?, 'HELD')", slotId);
            jdbcTemplate.update(
                    "INSERT INTO holds (hold_id, session_id, user_id, slot_id, expires_at, status) " +
                    "VALUES (?, ?, ?, ?, ?, 'ACTIVE')",
                    holdId, sessionId, userId, slotId,
                    Timestamp.from(Instant.now().minusSeconds(60)));
        }

        // Two concurrent instances — SKIP LOCKED ensures each hold is processed exactly once
        Thread t1 = new Thread(() -> holdExpiryJob.expireHolds());
        Thread t2 = new Thread(() -> holdExpiryJob.expireHolds());
        t1.start();
        t2.start();
        t1.join();
        t2.join();

        Integer expiredCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM holds WHERE status = 'EXPIRED'", Integer.class);
        assertThat(expiredCount).isEqualTo(holdCount);

        Integer activeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM holds WHERE status = 'ACTIVE'", Integer.class);
        assertThat(activeCount).isEqualTo(0);
    }
}
