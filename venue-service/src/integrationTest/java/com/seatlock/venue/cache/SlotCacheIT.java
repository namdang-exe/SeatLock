package com.seatlock.venue.cache;

import com.seatlock.venue.AbstractIntegrationTest;
import com.seatlock.venue.dto.CreateVenueRequest;
import com.seatlock.venue.dto.GenerateSlotsRequest;
import com.seatlock.venue.dto.SlotResponse;
import com.seatlock.venue.dto.VenueResponse;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SlotCacheIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Value("${seatlock.jwt.secret}")
    private String jwtSecret;

    @BeforeEach
    void clearRedis() {
        stringRedisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    private String adminToken() {
        return Jwts.builder()
                .subject("admin@example.com")
                .claim("userId", UUID.randomUUID().toString())
                .claim("role", "ADMIN")
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    private HttpHeaders adminHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken());
        return headers;
    }

    /** Creates a venue and generates slots for a Monday, returns the venueId. */
    private String setupVenueWithSlots() {
        CreateVenueRequest venueReq = new CreateVenueRequest("Cache Test Venue", "1 Test St", "Springfield", "IL");
        ResponseEntity<VenueResponse> venueResp = restTemplate.exchange(
                "/api/v1/admin/venues", HttpMethod.POST,
                new HttpEntity<>(venueReq, adminHeaders()), VenueResponse.class);
        String venueId = venueResp.getBody().venueId();

        // 2024-02-19 is a Monday — generates 8 slots
        GenerateSlotsRequest genReq = new GenerateSlotsRequest(
                LocalDate.of(2024, 2, 19), LocalDate.of(2024, 2, 19));
        restTemplate.exchange("/api/v1/admin/venues/" + venueId + "/slots/generate",
                HttpMethod.POST, new HttpEntity<>(genReq, adminHeaders()),
                new ParameterizedTypeReference<List<SlotResponse>>() {});

        return venueId;
    }

    @Test
    void firstRequest_populatesRedisKey() {
        String venueId = setupVenueWithSlots();

        restTemplate.exchange(
                "/api/v1/venues/" + venueId + "/slots?date=2024-02-19",
                HttpMethod.GET, HttpEntity.EMPTY,
                new ParameterizedTypeReference<List<SlotResponse>>() {});

        assertThat(stringRedisTemplate.hasKey("slots:" + venueId + ":2024-02-19")).isTrue();
    }

    @Test
    void secondRequestWithinTtl_redisKeyStillPresent_andReturnsConsistentData() {
        String venueId = setupVenueWithSlots();
        String url = "/api/v1/venues/" + venueId + "/slots?date=2024-02-19";

        ResponseEntity<List<SlotResponse>> first = restTemplate.exchange(
                url, HttpMethod.GET, HttpEntity.EMPTY, new ParameterizedTypeReference<>() {});

        // Key must be present before second request (TTL is 1s in test profile)
        assertThat(stringRedisTemplate.hasKey("slots:" + venueId + ":2024-02-19")).isTrue();

        ResponseEntity<List<SlotResponse>> second = restTemplate.exchange(
                url, HttpMethod.GET, HttpEntity.EMPTY, new ParameterizedTypeReference<>() {});

        assertThat(second.getBody()).hasSize(first.getBody().size());
        assertThat(second.getBody()).containsExactlyInAnyOrderElementsOf(first.getBody());
    }

    @Test
    void cacheKey_expiresAfterTtl() throws InterruptedException {
        String venueId = setupVenueWithSlots();

        restTemplate.exchange(
                "/api/v1/venues/" + venueId + "/slots?date=2024-02-19",
                HttpMethod.GET, HttpEntity.EMPTY,
                new ParameterizedTypeReference<List<SlotResponse>>() {});

        String key = "slots:" + venueId + ":2024-02-19";
        assertThat(stringRedisTemplate.hasKey(key)).isTrue();

        Thread.sleep(1500);

        assertThat(stringRedisTemplate.hasKey(key)).isFalse();
    }

    @Test
    void statusFilter_appliedCorrectlyToCachedData() {
        String venueId = setupVenueWithSlots();

        // Warm the cache (all 8 slots, all AVAILABLE)
        restTemplate.exchange(
                "/api/v1/venues/" + venueId + "/slots?date=2024-02-19",
                HttpMethod.GET, HttpEntity.EMPTY,
                new ParameterizedTypeReference<List<SlotResponse>>() {});

        ResponseEntity<List<SlotResponse>> available = restTemplate.exchange(
                "/api/v1/venues/" + venueId + "/slots?date=2024-02-19&status=AVAILABLE",
                HttpMethod.GET, HttpEntity.EMPTY, new ParameterizedTypeReference<>() {});

        assertThat(available.getBody()).hasSize(8);
        assertThat(available.getBody()).allMatch(s -> s.status().equals("AVAILABLE"));

        ResponseEntity<List<SlotResponse>> held = restTemplate.exchange(
                "/api/v1/venues/" + venueId + "/slots?date=2024-02-19&status=HELD",
                HttpMethod.GET, HttpEntity.EMPTY, new ParameterizedTypeReference<>() {});

        assertThat(held.getBody()).isEmpty();
    }

    @Test
    void endTime_derivedCorrectlyFromCachedData() {
        String venueId = setupVenueWithSlots();

        // First request populates cache
        restTemplate.exchange(
                "/api/v1/venues/" + venueId + "/slots?date=2024-02-19",
                HttpMethod.GET, HttpEntity.EMPTY,
                new ParameterizedTypeReference<List<SlotResponse>>() {});

        // Second request served from cache
        ResponseEntity<List<SlotResponse>> response = restTemplate.exchange(
                "/api/v1/venues/" + venueId + "/slots?date=2024-02-19",
                HttpMethod.GET, HttpEntity.EMPTY, new ParameterizedTypeReference<>() {});

        response.getBody().forEach(slot ->
                assertThat(slot.endTime()).isEqualTo(slot.startTime().plus(60, ChronoUnit.MINUTES)));
    }
}
