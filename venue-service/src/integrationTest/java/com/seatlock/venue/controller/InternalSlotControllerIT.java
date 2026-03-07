package com.seatlock.venue.controller;

import com.seatlock.venue.AbstractIntegrationTest;
import com.seatlock.venue.domain.Slot;
import com.seatlock.venue.domain.Venue;
import com.seatlock.venue.domain.VenueStatus;
import com.seatlock.venue.repository.SlotRepository;
import com.seatlock.venue.repository.VenueRepository;
import com.seatlock.common.security.Hs256JwtProvider;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InternalSlotControllerIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private VenueRepository venueRepository;

    @Autowired
    private SlotRepository slotRepository;

    @Value("${seatlock.service-jwt.secret}")
    private String serviceJwtSecret;

    @Value("${seatlock.jwt.secret}")
    private String userJwtSecret;

    private Slot savedSlot;

    @BeforeEach
    void setUp() {
        slotRepository.deleteAll();
        venueRepository.deleteAll();

        Venue venue = new Venue();
        venue.setName("Test Venue");
        venue.setAddress("1 Test St");
        venue.setCity("London");
        venue.setState("England");
        venue.setStatus(VenueStatus.ACTIVE);
        venue = venueRepository.save(venue);

        Slot slot = new Slot();
        slot.setVenueId(venue.getVenueId());
        slot.setStartTime(Instant.now().plusSeconds(3600));
        savedSlot = slotRepository.save(slot);
    }

    @Test
    void getSlots_withValidServiceJwt_returnsSlots() {
        String token = buildServiceJwt(serviceJwtSecret, "booking-service", "seatlock-internal", 5);
        ResponseEntity<String> response = getWithAuth("/api/v1/internal/slots?ids=" + savedSlot.getSlotId(), token);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains(savedSlot.getSlotId().toString());
    }

    @Test
    void getSlots_withNoToken_returns401() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/internal/slots?ids=" + savedSlot.getSlotId(), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void getSlots_withUserJwt_returns401() {
        // A user JWT has sub=email and role claim — NOT sub=booking-service
        String userToken = buildUserJwt(userJwtSecret, "user@example.com", "USER");
        ResponseEntity<String> response = getWithAuth("/api/v1/internal/slots?ids=" + savedSlot.getSlotId(), userToken);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void getSlots_withExpiredServiceJwt_returns401() {
        String expiredToken = buildServiceJwt(serviceJwtSecret, "booking-service", "seatlock-internal", -1);
        ResponseEntity<String> response = getWithAuth("/api/v1/internal/slots?ids=" + savedSlot.getSlotId(), expiredToken);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void getSlots_withUnknownSlotId_returns404() {
        String token = buildServiceJwt(serviceJwtSecret, "booking-service", "seatlock-internal", 5);
        ResponseEntity<String> response = getWithAuth("/api/v1/internal/slots?ids=" + UUID.randomUUID(), token);
        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    private ResponseEntity<String> getWithAuth(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private String buildServiceJwt(String secret, String subject, String issuer, long ttlMinutes) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(subject)
                .issuer(issuer)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttlMinutes, ChronoUnit.MINUTES)))
                .signWith(key)
                .compact();
    }

    private String buildUserJwt(String secret, String email, String role) {
        return new Hs256JwtProvider(secret)
                .issue(null, email, role, Instant.now().plus(24, ChronoUnit.HOURS));
    }
}
