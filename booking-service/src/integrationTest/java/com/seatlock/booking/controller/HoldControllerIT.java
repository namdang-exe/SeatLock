package com.seatlock.booking.controller;

import com.seatlock.booking.AbstractIntegrationTest;
import com.seatlock.booking.client.InternalSlotResponse;
import com.seatlock.booking.client.SlotVerificationClient;
import com.seatlock.booking.exception.VenueServiceUnavailableException;
import com.seatlock.booking.dto.HoldResponse;
import com.seatlock.common.security.Hs256JwtProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

class HoldControllerIT extends AbstractIntegrationTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired com.seatlock.booking.redis.RedisHoldRepository redisHoldRepository;

    @MockitoBean SlotVerificationClient slotVerificationClient;

    @Value("${seatlock.jwt.secret}")
    String jwtSecret;

    UUID userId;
    UUID slotId;
    UUID venueId;

    @BeforeEach
    void setUp() {
        userId  = UUID.randomUUID();
        slotId  = UUID.randomUUID();
        venueId = UUID.randomUUID();

        // Clean up before each test — FK-safe order: bookings → holds → slots → users
        jdbcTemplate.execute("DELETE FROM bookings");
        jdbcTemplate.execute("DELETE FROM holds");
        jdbcTemplate.execute("DELETE FROM slots");
        jdbcTemplate.execute("DELETE FROM users");

        // Insert test user and slot
        jdbcTemplate.update("INSERT INTO users (user_id) VALUES (?)", userId);
        jdbcTemplate.update("INSERT INTO slots (slot_id, status) VALUES (?, 'AVAILABLE')", slotId);

        // Default mock: slot exists
        when(slotVerificationClient.verify(anyList()))
                .thenReturn(List.of(new InternalSlotResponse(
                        slotId, venueId,
                        Instant.now().plus(1, ChronoUnit.HOURS),
                        "AVAILABLE")));
    }

    @Test
    void happyPath_createsHoldInRedisAndPostgres() {
        ResponseEntity<HoldResponse> response = postHolds(userId, List.of(slotId), UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        HoldResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.holds()).hasSize(1);
        assertThat(body.holds().get(0).slotId()).isEqualTo(slotId);
        assertThat(body.expiresAt()).isAfter(Instant.now());

        // Postgres: hold row inserted
        Integer holdCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM holds WHERE slot_id = ?", Integer.class, slotId);
        assertThat(holdCount).isEqualTo(1);

        // Postgres: slot status updated to HELD
        String slotStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM slots WHERE slot_id = ?", String.class, slotId);
        assertThat(slotStatus).isEqualTo("HELD");

        // Redis: hold key exists with TTL ≈ 1800s (allow ±10s for test execution time)
        Long ttl = redisHoldRepository.getTtlSeconds(slotId);
        assertThat(ttl).isNotNull().isGreaterThan(1790L);
    }

    @Test
    void missingIdempotencyKey_returns400() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + issueToken(userId));

        HttpEntity<String> entity = new HttpEntity<>(
                "{\"slotIds\":[\"" + slotId + "\"]}", headers);

        ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/holds", entity, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "MISSING_IDEMPOTENCY_KEY");
    }

    @Test
    void slotAlreadyHeld_setnxFails_returns409() {
        UUID sessionId1 = UUID.randomUUID();
        UUID sessionId2 = UUID.randomUUID();

        // First request succeeds
        ResponseEntity<HoldResponse> first = postHolds(userId, List.of(slotId), sessionId1);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Second request for the same slot — Redis key already exists
        ResponseEntity<Map> second = restTemplate.postForEntity(
                "/api/v1/holds",
                holdEntity(userId, List.of(slotId), sessionId2),
                Map.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).containsEntry("error", "SLOT_NOT_AVAILABLE");
        @SuppressWarnings("unchecked")
        List<String> unavailableIds = (List<String>) second.getBody().get("unavailableSlotIds");
        assertThat(unavailableIds).containsExactly(slotId.toString());
    }

    @Test
    void slotHeldInPostgresButRedisKeyExpired_rowCountMismatch_returns409() {
        // Set slot status to HELD in Postgres (simulates ≤60s expiry-lag window):
        // Redis key is absent (SETNX will succeed), but slot is already HELD in Postgres
        jdbcTemplate.update("UPDATE slots SET status = 'HELD' WHERE slot_id = ?", slotId);

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/v1/holds",
                holdEntity(userId, List.of(slotId), UUID.randomUUID()),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("error", "SLOT_NOT_AVAILABLE");

        // Postgres: no new hold rows inserted (transaction rolled back)
        Integer holdCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM holds WHERE slot_id = ?", Integer.class, slotId);
        assertThat(holdCount).isEqualTo(0);
    }

    @Test
    void idempotentReplay_sameKey_returns200WithExistingHolds() {
        UUID sessionId = UUID.randomUUID();

        ResponseEntity<HoldResponse> first = postHolds(userId, List.of(slotId), sessionId);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Replay with the same Idempotency-Key
        ResponseEntity<HoldResponse> replay = postHolds(userId, List.of(slotId), sessionId);

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody().sessionId()).isEqualTo(first.getBody().sessionId());
        assertThat(replay.getBody().holds().get(0).holdId())
                .isEqualTo(first.getBody().holds().get(0).holdId());

        // Still only one hold row in Postgres
        Integer holdCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM holds WHERE slot_id = ?", Integer.class, slotId);
        assertThat(holdCount).isEqualTo(1);
    }

    @Test
    void venueServiceUnavailable_circuitBreakerFallback_returns503() {
        // Simulate what happens when the circuit breaker opens: the fallback throws VenueServiceUnavailableException
        when(slotVerificationClient.verify(anyList()))
                .thenThrow(new VenueServiceUnavailableException());

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/v1/holds",
                holdEntity(userId, List.of(slotId), UUID.randomUUID()),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("error", "SERVICE_UNAVAILABLE");
    }

    @Test
    void concurrency_10ThreadsSameSlot_exactlyOneWins() throws InterruptedException {
        int threadCount = 10;
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                ready.countDown();
                try { start.await(); } catch (InterruptedException ignored) {}

                ResponseEntity<HoldResponse> resp = postHolds(userId, List.of(slotId), UUID.randomUUID());
                if (resp.getStatusCode() == HttpStatus.OK) {
                    successCount.incrementAndGet();
                } else if (resp.getStatusCode() == HttpStatus.CONFLICT) {
                    conflictCount.incrementAndGet();
                }
            });
        }

        ready.await();
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(threadCount - 1);
    }

    // --- helpers ---

    private ResponseEntity<HoldResponse> postHolds(UUID userId, List<UUID> slotIds, UUID sessionId) {
        return restTemplate.postForEntity("/api/v1/holds",
                holdEntity(userId, slotIds, sessionId),
                HoldResponse.class);
    }

    private HttpEntity<String> holdEntity(UUID userId, List<UUID> slotIds, UUID sessionId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + issueToken(userId));
        headers.set("Idempotency-Key", sessionId.toString());

        String ids = slotIds.stream()
                .map(id -> "\"" + id + "\"")
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        String body = "{\"slotIds\":[" + ids + "]}";

        return new HttpEntity<>(body, headers);
    }

    private String issueToken(UUID userId) {
        return new Hs256JwtProvider(jwtSecret)
                .issue(userId, "test@example.com", "USER",
                        Instant.now().plus(1, ChronoUnit.HOURS));
    }
}
