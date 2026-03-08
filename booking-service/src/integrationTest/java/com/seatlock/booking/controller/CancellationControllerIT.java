package com.seatlock.booking.controller;

import com.seatlock.booking.AbstractIntegrationTest;
import com.seatlock.booking.client.InternalSlotResponse;
import com.seatlock.booking.client.SlotVerificationClient;
import com.seatlock.booking.dto.AdminBookingResponse;
import com.seatlock.booking.dto.BookingHistoryResponse;
import com.seatlock.booking.dto.BookingResponse;
import com.seatlock.booking.dto.CancelResponse;
import com.seatlock.booking.dto.HoldResponse;
import com.seatlock.booking.redis.RedisHoldRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.seatlock.common.security.Hs256JwtProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
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

class CancellationControllerIT extends AbstractIntegrationTest {

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
    // Slot 48h in the future — safely within the cancellation window
    Instant slotStartTime;

    @BeforeEach
    void setUp() {
        userId       = UUID.randomUUID();
        slotId       = UUID.randomUUID();
        venueId      = UUID.randomUUID();
        slotStartTime = Instant.now().plus(48, ChronoUnit.HOURS);

        jdbcTemplate.execute("DELETE FROM bookings");
        jdbcTemplate.execute("DELETE FROM holds");
        jdbcTemplate.execute("DELETE FROM slots");
        jdbcTemplate.execute("DELETE FROM venues");
        jdbcTemplate.execute("DELETE FROM users");

        jdbcTemplate.update("INSERT INTO users (user_id) VALUES (?)", userId);
        jdbcTemplate.update("INSERT INTO venues (venue_id, name) VALUES (?, ?)", venueId, "Test Venue");
        jdbcTemplate.update(
                "INSERT INTO slots (slot_id, venue_id, status, start_time) VALUES (?, ?, 'AVAILABLE', ?)",
                slotId, venueId, Timestamp.from(slotStartTime));

        when(slotVerificationClient.verify(anyList()))
                .thenReturn(List.of(new InternalSlotResponse(slotId, venueId, "Test Venue", slotStartTime, "AVAILABLE")));
    }

    // ---- Cancel happy path ----

    @Test
    void cancel_happyPath_slotAvailableHoldReleasedStaleRedisKeyDeleted() {
        // Arrange: hold → confirm (slot becomes BOOKED, Redis key DEL'd by confirmBooking)
        HoldResponse holdResp = createHold(userId, slotId);
        UUID sessionId = holdResp.sessionId();
        String confirmationNumber = confirmBooking(userId, sessionId).getBody().confirmationNumber();

        // Plant a stale Redis hold key — simulates ADR-008 crash-before-DEL scenario
        String stalePayload = String.format(
                "{\"holdId\":\"%s\",\"userId\":\"%s\",\"sessionId\":\"%s\",\"expiresAt\":\"%s\"}",
                UUID.randomUUID(), userId, sessionId, Instant.now().plusSeconds(1800));
        stringRedisTemplate.opsForValue().set("hold:" + slotId, stalePayload,
                Duration.ofSeconds(60));
        assertThat(redisHoldRepository.getRawHold(slotId)).isNotNull(); // planted

        // Act
        ResponseEntity<CancelResponse> response = postCancel(userId, confirmationNumber);

        // Assert HTTP
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        CancelResponse body = response.getBody();
        assertThat(body.confirmationNumber()).isEqualTo(confirmationNumber);
        assertThat(body.cancelledAt()).isNotNull();
        assertThat(body.bookings()).hasSize(1);
        assertThat(body.bookings().get(0).slotId()).isEqualTo(slotId);
        assertThat(body.bookings().get(0).status()).isEqualTo("CANCELLED");

        // Postgres: booking → CANCELLED
        String bookingStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM bookings WHERE confirmation_number = ?", String.class, confirmationNumber);
        assertThat(bookingStatus).isEqualTo("CANCELLED");

        // Postgres: slot → AVAILABLE
        String slotStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM slots WHERE slot_id = ?", String.class, slotId);
        assertThat(slotStatus).isEqualTo("AVAILABLE");

        // Postgres: hold → RELEASED
        String holdStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM holds WHERE session_id = ?", String.class, sessionId);
        assertThat(holdStatus).isEqualTo("RELEASED");

        // Redis: stale key DEL'd by cancel step 6 (ADR-008 stale key cleanup)
        assertThat(redisHoldRepository.getRawHold(slotId)).isNull();
    }

    // ---- Idempotent cancel ----

    @Test
    void cancel_alreadyCancelled_returns200WithNoAdditionalChanges() {
        HoldResponse holdResp = createHold(userId, slotId);
        UUID sessionId = holdResp.sessionId();
        String confirmationNumber = confirmBooking(userId, sessionId).getBody().confirmationNumber();

        // First cancel
        ResponseEntity<CancelResponse> first = postCancel(userId, confirmationNumber);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Second cancel — idempotent
        ResponseEntity<CancelResponse> replay = postCancel(userId, confirmationNumber);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody().confirmationNumber()).isEqualTo(confirmationNumber);
        assertThat(replay.getBody().bookings().get(0).status()).isEqualTo("CANCELLED");

        // Still only one booking row
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE confirmation_number = ?",
                Integer.class, confirmationNumber);
        assertThat(count).isEqualTo(1);
    }

    // ---- 24h window closed ----

    @Test
    void cancel_slotWithin24h_returns409CancellationWindowClosed() {
        // Insert a slot that starts in 12h (within the 24h window)
        UUID nearSlotId = UUID.randomUUID();
        Instant nearStartTime = Instant.now().plus(12, ChronoUnit.HOURS);
        jdbcTemplate.update(
                "INSERT INTO slots (slot_id, venue_id, status, start_time) VALUES (?, ?, 'AVAILABLE', ?)",
                nearSlotId, venueId, Timestamp.from(nearStartTime));

        when(slotVerificationClient.verify(anyList()))
                .thenReturn(List.of(new InternalSlotResponse(nearSlotId, venueId, "Test Venue", nearStartTime, "AVAILABLE")));

        HoldResponse holdResp = createHold(userId, nearSlotId);
        UUID sessionId = holdResp.sessionId();
        String confirmationNumber = confirmBooking(userId, sessionId).getBody().confirmationNumber();

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/bookings/" + confirmationNumber + "/cancel",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(userId)),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("error", "CANCELLATION_WINDOW_CLOSED");
    }

    // ---- Booking history ----

    @Test
    void getHistory_returnsBookingsGroupedByConfirmationNumber() {
        // Create two separate bookings
        HoldResponse hold1 = createHold(userId, slotId);
        String cn1 = confirmBooking(userId, hold1.sessionId()).getBody().confirmationNumber();

        UUID slotId2 = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO slots (slot_id, venue_id, status, start_time) VALUES (?, ?, 'AVAILABLE', ?)",
                slotId2, venueId, Timestamp.from(Instant.now().plus(72, ChronoUnit.HOURS)));
        when(slotVerificationClient.verify(anyList()))
                .thenReturn(List.of(new InternalSlotResponse(slotId2, venueId, "Test Venue",
                        Instant.now().plus(72, ChronoUnit.HOURS), "AVAILABLE")));

        HoldResponse hold2 = createHold(userId, slotId2);
        String cn2 = confirmBooking(userId, hold2.sessionId()).getBody().confirmationNumber();

        // GET /bookings
        ResponseEntity<BookingHistoryResponse> response = restTemplate.exchange(
                "/api/v1/bookings",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(userId)),
                BookingHistoryResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        BookingHistoryResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.sessions()).hasSize(2);

        // Each session has one booking
        assertThat(body.sessions()).allMatch(s -> s.bookings().size() == 1);

        // Both confirmation numbers present
        List<String> returnedCNs = body.sessions().stream()
                .map(BookingHistoryResponse.BookingSession::confirmationNumber)
                .toList();
        assertThat(returnedCNs).containsExactlyInAnyOrder(cn1, cn2);
    }

    // ---- Admin endpoint role checks ----

    @Test
    void adminGetBookings_adminRole_returns200() {
        HoldResponse holdResp = createHold(userId, slotId);
        confirmBooking(userId, holdResp.sessionId());

        ResponseEntity<AdminBookingResponse> response = restTemplate.exchange(
                "/api/v1/admin/venues/" + venueId + "/bookings",
                HttpMethod.GET,
                new HttpEntity<>(adminAuthHeaders()),
                AdminBookingResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().bookings()).hasSize(1);
        assertThat(response.getBody().bookings().get(0).slotId()).isEqualTo(slotId);
        assertThat(response.getBody().bookings().get(0).status()).isEqualTo("CONFIRMED");
    }

    @Test
    void adminGetBookings_userRole_returns403() {
        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/admin/venues/" + venueId + "/bookings",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(userId)),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- helpers ---

    private HoldResponse createHold(UUID userId, UUID slotId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + issueUserToken(userId));
        headers.set("Idempotency-Key", UUID.randomUUID().toString());

        ResponseEntity<HoldResponse> resp = restTemplate.postForEntity(
                "/api/v1/holds",
                new HttpEntity<>("{\"slotIds\":[\"" + slotId + "\"]}", headers),
                HoldResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    private ResponseEntity<BookingResponse> confirmBooking(UUID userId, UUID sessionId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + issueUserToken(userId));
        ResponseEntity<BookingResponse> resp = restTemplate.postForEntity(
                "/api/v1/bookings",
                new HttpEntity<>("{\"sessionId\":\"" + sessionId + "\"}", headers),
                BookingResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return resp;
    }

    private ResponseEntity<CancelResponse> postCancel(UUID userId, String confirmationNumber) {
        return restTemplate.exchange(
                "/api/v1/bookings/" + confirmationNumber + "/cancel",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(userId)),
                CancelResponse.class);
    }

    private HttpHeaders authHeaders(UUID userId) {
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", "Bearer " + issueUserToken(userId));
        return h;
    }

    private HttpHeaders adminAuthHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", "Bearer " + issueAdminToken());
        return h;
    }

    private String issueUserToken(UUID userId) {
        return new Hs256JwtProvider(jwtSecret)
                .issue(userId, "user@example.com", "USER",
                        Instant.now().plus(1, ChronoUnit.HOURS));
    }

    private String issueAdminToken() {
        return new Hs256JwtProvider(jwtSecret)
                .issue(UUID.randomUUID(), "admin@example.com", "ADMIN",
                        Instant.now().plus(1, ChronoUnit.HOURS));
    }
}
