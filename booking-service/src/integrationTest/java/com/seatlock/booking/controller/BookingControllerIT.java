package com.seatlock.booking.controller;

import com.seatlock.booking.AbstractIntegrationTest;
import com.seatlock.booking.client.InternalSlotResponse;
import com.seatlock.booking.client.SlotVerificationClient;
import com.seatlock.booking.dto.BookingResponse;
import com.seatlock.booking.dto.HoldResponse;
import com.seatlock.booking.redis.RedisHoldRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

class BookingControllerIT extends AbstractIntegrationTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RedisHoldRepository redisHoldRepository;
    @Autowired StringRedisTemplate stringRedisTemplate;

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

        jdbcTemplate.execute("DELETE FROM bookings");
        jdbcTemplate.execute("DELETE FROM holds");
        jdbcTemplate.execute("DELETE FROM slots");
        jdbcTemplate.execute("DELETE FROM users");

        jdbcTemplate.update("INSERT INTO users (user_id) VALUES (?)", userId);
        jdbcTemplate.update("INSERT INTO slots (slot_id, status) VALUES (?, 'AVAILABLE')", slotId);

        when(slotVerificationClient.verify(anyList()))
                .thenReturn(List.of(new InternalSlotResponse(
                        slotId, venueId,
                        Instant.now().plus(1, ChronoUnit.HOURS),
                        "AVAILABLE")));
    }

    @Test
    void happyPath_returnsCreatedWithConfirmationNumber() {
        // Create a hold first
        HoldResponse holdResp = createHold(userId, slotId);
        UUID sessionId = holdResp.sessionId();

        // Confirm the booking
        ResponseEntity<BookingResponse> response = postBookings(userId, sessionId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        BookingResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.confirmationNumber()).matches("SL-\\d{8}-\\d{4}");
        assertThat(body.sessionId()).isEqualTo(sessionId);
        assertThat(body.bookings()).hasSize(1);
        assertThat(body.bookings().get(0).slotId()).isEqualTo(slotId);
        assertThat(body.bookings().get(0).status()).isEqualTo("CONFIRMED");

        // Postgres: booking row inserted
        Integer bookingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE session_id = ?", Integer.class, sessionId);
        assertThat(bookingCount).isEqualTo(1);

        // Postgres: slot status → BOOKED
        String slotStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM slots WHERE slot_id = ?", String.class, slotId);
        assertThat(slotStatus).isEqualTo("BOOKED");

        // Postgres: hold status → CONFIRMED
        String holdStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM holds WHERE session_id = ?", String.class, sessionId);
        assertThat(holdStatus).isEqualTo("CONFIRMED");

        // Redis: hold key deleted after Postgres commit
        assertThat(redisHoldRepository.getRawHold(slotId)).isNull();
    }

    @Test
    void holdExpired_redisKeyMissing_returns409() {
        HoldResponse holdResp = createHold(userId, slotId);
        UUID sessionId = holdResp.sessionId();

        // Simulate expired hold: manually delete Redis key
        redisHoldRepository.del(slotId);

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/v1/bookings",
                bookingEntity(userId, sessionId),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("error", "HOLD_EXPIRED");
    }

    @Test
    void holdMismatch_differentHoldIdInRedis_returns409() {
        HoldResponse holdResp = createHold(userId, slotId);
        UUID sessionId = holdResp.sessionId();

        // Overwrite Redis key with a payload containing a different holdId
        UUID fakeHoldId = UUID.randomUUID();
        String fakePayload = String.format(
                "{\"holdId\":\"%s\",\"userId\":\"%s\",\"sessionId\":\"%s\",\"expiresAt\":\"%s\"}",
                fakeHoldId, userId, sessionId, Instant.now().plusSeconds(1800));
        stringRedisTemplate.opsForValue().set("hold:" + slotId, fakePayload, Duration.ofSeconds(60));

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/v1/bookings",
                bookingEntity(userId, sessionId),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("error", "HOLD_MISMATCH");
    }

    @Test
    void idempotentReplay_sameSessionId_returns201WithExistingData() {
        HoldResponse holdResp = createHold(userId, slotId);
        UUID sessionId = holdResp.sessionId();

        ResponseEntity<BookingResponse> first = postBookings(userId, sessionId);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<BookingResponse> replay = postBookings(userId, sessionId);

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody().confirmationNumber())
                .isEqualTo(first.getBody().confirmationNumber());
        assertThat(replay.getBody().bookings().get(0).bookingId())
                .isEqualTo(first.getBody().bookings().get(0).bookingId());

        // Still only one booking row
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE session_id = ?", Integer.class, sessionId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void crashRecovery_postgresCommittedRedisDelSkipped_returns201ViaIdempotencyCheck() {
        // Simulate crash: Postgres committed but Redis DEL never happened
        UUID sessionId = UUID.randomUUID();
        UUID holdIdVal = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        String confirmationNumber = "SL-20260303-9999";

        // Insert the hold (already CONFIRMED as it would be after Postgres commit)
        jdbcTemplate.update(
                "INSERT INTO holds (hold_id, session_id, user_id, slot_id, expires_at, status) " +
                "VALUES (?, ?, ?, ?, ?, 'CONFIRMED')",
                holdIdVal, sessionId, userId, slotId,
                java.sql.Timestamp.from(Instant.now().plusSeconds(1800)));

        // Insert the booking (as Postgres transaction committed)
        jdbcTemplate.update(
                "INSERT INTO bookings (booking_id, session_id, confirmation_number, user_id, slot_id, hold_id, status) " +
                "VALUES (?, ?, ?, ?, ?, ?, 'CONFIRMED')",
                bookingId, sessionId, confirmationNumber, userId, slotId, holdIdVal);

        // Redis hold key still present (crash before DEL)
        String payload = String.format(
                "{\"holdId\":\"%s\",\"userId\":\"%s\",\"sessionId\":\"%s\",\"expiresAt\":\"%s\"}",
                holdIdVal, userId, sessionId, Instant.now().plusSeconds(1800));
        stringRedisTemplate.opsForValue().set("hold:" + slotId, payload, Duration.ofSeconds(60));

        // POST /bookings: idempotency check finds the CONFIRMED booking and returns 201
        ResponseEntity<BookingResponse> response = postBookings(userId, sessionId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().confirmationNumber()).isEqualTo(confirmationNumber);
        assertThat(response.getBody().bookings().get(0).bookingId()).isEqualTo(bookingId);

        // Still only one booking row — no duplicate
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE session_id = ?", Integer.class, sessionId);
        assertThat(count).isEqualTo(1);
    }

    // --- helpers ---

    private HoldResponse createHold(UUID userId, UUID slotId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + issueToken(userId));
        headers.set("Idempotency-Key", UUID.randomUUID().toString());

        HttpEntity<String> entity = new HttpEntity<>(
                "{\"slotIds\":[\"" + slotId + "\"]}", headers);

        ResponseEntity<HoldResponse> response = restTemplate.postForEntity(
                "/api/v1/holds", entity, HoldResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<BookingResponse> postBookings(UUID userId, UUID sessionId) {
        return restTemplate.postForEntity("/api/v1/bookings",
                bookingEntity(userId, sessionId), BookingResponse.class);
    }

    private HttpEntity<String> bookingEntity(UUID userId, UUID sessionId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + issueToken(userId));
        return new HttpEntity<>("{\"sessionId\":\"" + sessionId + "\"}", headers);
    }

    private String issueToken(UUID userId) {
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject("test@example.com")
                .claim("userId", userId.toString())
                .claim("role", "USER")
                .expiration(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .signWith(key)
                .compact();
    }
}
