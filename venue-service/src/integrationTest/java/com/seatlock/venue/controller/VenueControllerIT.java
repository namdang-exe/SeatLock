package com.seatlock.venue.controller;

import com.seatlock.venue.AbstractIntegrationTest;
import com.seatlock.venue.dto.CreateVenueRequest;
import com.seatlock.venue.dto.GenerateSlotsRequest;
import com.seatlock.venue.dto.SlotResponse;
import com.seatlock.venue.dto.UpdateVenueStatusRequest;
import com.seatlock.venue.dto.VenueResponse;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class VenueControllerIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Value("${seatlock.jwt.secret}")
    private String jwtSecret;

    // --- Helpers ---

    private String token(String role) {
        return Jwts.builder()
                .subject("test@example.com")
                .claim("userId", UUID.randomUUID().toString())
                .claim("role", role)
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    private HttpHeaders authHeaders(String role) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token(role));
        return headers;
    }

    private CreateVenueRequest sampleVenue(String suffix) {
        return new CreateVenueRequest("Venue " + suffix, "123 Main St", "Springfield", "IL");
    }

    // --- Tests ---

    @Test
    void createVenue_asAdmin_returns201() {
        HttpEntity<CreateVenueRequest> req = new HttpEntity<>(sampleVenue("A"), authHeaders("ADMIN"));

        ResponseEntity<VenueResponse> response = restTemplate.exchange(
                "/api/v1/admin/venues", HttpMethod.POST, req, VenueResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().venueId()).isNotNull();
        assertThat(response.getBody().name()).isEqualTo("Venue A");
        assertThat(response.getBody().status()).isEqualTo("ACTIVE");
    }

    @Test
    void createVenue_asUser_returns403() {
        HttpEntity<CreateVenueRequest> req = new HttpEntity<>(sampleVenue("B"), authHeaders("USER"));

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/venues", HttpMethod.POST, req, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void createVenue_noToken_returns401() {
        HttpEntity<CreateVenueRequest> req = new HttpEntity<>(sampleVenue("C"));

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/venues", HttpMethod.POST, req, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void getVenues_noToken_returns200WithActiveVenuesOnly() {
        // Create one ACTIVE and one INACTIVE venue
        HttpEntity<CreateVenueRequest> req = new HttpEntity<>(sampleVenue("Active"), authHeaders("ADMIN"));
        ResponseEntity<VenueResponse> created = restTemplate.exchange(
                "/api/v1/admin/venues", HttpMethod.POST, req, VenueResponse.class);
        String venueId = created.getBody().venueId();

        HttpEntity<UpdateVenueStatusRequest> patchReq = new HttpEntity<>(
                new UpdateVenueStatusRequest(com.seatlock.venue.domain.VenueStatus.INACTIVE), authHeaders("ADMIN"));
        restTemplate.exchange("/api/v1/admin/venues/" + venueId + "/status", HttpMethod.PATCH, patchReq, VenueResponse.class);

        // GET venues without a token (public endpoint)
        ResponseEntity<List<VenueResponse>> response = restTemplate.exchange(
                "/api/v1/venues", HttpMethod.GET, HttpEntity.EMPTY,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).noneMatch(v -> v.venueId().equals(venueId));
    }

    @Test
    void updateVenueStatus_asAdmin_returns200() {
        HttpEntity<CreateVenueRequest> createReq = new HttpEntity<>(sampleVenue("ToInactivate"), authHeaders("ADMIN"));
        ResponseEntity<VenueResponse> created = restTemplate.exchange(
                "/api/v1/admin/venues", HttpMethod.POST, createReq, VenueResponse.class);
        String venueId = created.getBody().venueId();

        HttpEntity<UpdateVenueStatusRequest> patchReq = new HttpEntity<>(
                new UpdateVenueStatusRequest(com.seatlock.venue.domain.VenueStatus.INACTIVE), authHeaders("ADMIN"));
        ResponseEntity<VenueResponse> response = restTemplate.exchange(
                "/api/v1/admin/venues/" + venueId + "/status", HttpMethod.PATCH, patchReq, VenueResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo("INACTIVE");
    }

    @Test
    void generateSlots_asAdmin_returns201WithCorrectSlots() {
        // Create a venue first
        HttpEntity<CreateVenueRequest> createReq = new HttpEntity<>(sampleVenue("SlotGen"), authHeaders("ADMIN"));
        ResponseEntity<VenueResponse> created = restTemplate.exchange(
                "/api/v1/admin/venues", HttpMethod.POST, createReq, VenueResponse.class);
        String venueId = created.getBody().venueId();

        // Generate slots for a week (Mon 2024-02-19 to Fri 2024-02-23 = 5 days × 8 slots = 40)
        GenerateSlotsRequest genReq = new GenerateSlotsRequest(
                LocalDate.of(2024, 2, 19), LocalDate.of(2024, 2, 23));
        HttpEntity<GenerateSlotsRequest> req = new HttpEntity<>(genReq, authHeaders("ADMIN"));
        ResponseEntity<List<SlotResponse>> response = restTemplate.exchange(
                "/api/v1/admin/venues/" + venueId + "/slots/generate", HttpMethod.POST, req,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).hasSize(40);
        assertThat(response.getBody()).allMatch(s -> s.status().equals("AVAILABLE"));
        assertThat(response.getBody()).allMatch(s -> s.endTime().isAfter(s.startTime()));
    }

    @Test
    void getSlots_byDate_returnsOnlyThatDay() {
        HttpEntity<CreateVenueRequest> createReq = new HttpEntity<>(sampleVenue("DateFilter"), authHeaders("ADMIN"));
        ResponseEntity<VenueResponse> created = restTemplate.exchange(
                "/api/v1/admin/venues", HttpMethod.POST, createReq, VenueResponse.class);
        String venueId = created.getBody().venueId();

        // Generate for 2 weekdays
        GenerateSlotsRequest genReq = new GenerateSlotsRequest(
                LocalDate.of(2024, 2, 19), LocalDate.of(2024, 2, 20));
        restTemplate.exchange("/api/v1/admin/venues/" + venueId + "/slots/generate",
                HttpMethod.POST, new HttpEntity<>(genReq, authHeaders("ADMIN")),
                new ParameterizedTypeReference<List<SlotResponse>>() {});

        // Query only Monday
        ResponseEntity<List<SlotResponse>> response = restTemplate.exchange(
                "/api/v1/venues/" + venueId + "/slots?date=2024-02-19",
                HttpMethod.GET, HttpEntity.EMPTY, new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(8);
    }

    @Test
    void getSlots_statusFilter_returnsOnlyMatching() {
        HttpEntity<CreateVenueRequest> createReq = new HttpEntity<>(sampleVenue("StatusFilter"), authHeaders("ADMIN"));
        ResponseEntity<VenueResponse> created = restTemplate.exchange(
                "/api/v1/admin/venues", HttpMethod.POST, createReq, VenueResponse.class);
        String venueId = created.getBody().venueId();

        GenerateSlotsRequest genReq = new GenerateSlotsRequest(
                LocalDate.of(2024, 3, 4), LocalDate.of(2024, 3, 4));
        restTemplate.exchange("/api/v1/admin/venues/" + venueId + "/slots/generate",
                HttpMethod.POST, new HttpEntity<>(genReq, authHeaders("ADMIN")),
                new ParameterizedTypeReference<List<SlotResponse>>() {});

        ResponseEntity<List<SlotResponse>> response = restTemplate.exchange(
                "/api/v1/venues/" + venueId + "/slots?date=2024-03-04&status=AVAILABLE",
                HttpMethod.GET, HttpEntity.EMPTY, new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotEmpty();
        assertThat(response.getBody()).allMatch(s -> s.status().equals("AVAILABLE"));
    }

    @Test
    void getSlots_inactiveVenue_returns404() {
        HttpEntity<CreateVenueRequest> createReq = new HttpEntity<>(sampleVenue("Inactive"), authHeaders("ADMIN"));
        ResponseEntity<VenueResponse> created = restTemplate.exchange(
                "/api/v1/admin/venues", HttpMethod.POST, createReq, VenueResponse.class);
        String venueId = created.getBody().venueId();

        HttpEntity<UpdateVenueStatusRequest> patchReq = new HttpEntity<>(
                new UpdateVenueStatusRequest(com.seatlock.venue.domain.VenueStatus.INACTIVE), authHeaders("ADMIN"));
        restTemplate.exchange("/api/v1/admin/venues/" + venueId + "/status",
                HttpMethod.PATCH, patchReq, VenueResponse.class);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/venues/" + venueId + "/slots",
                HttpMethod.GET, HttpEntity.EMPTY, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("VENUE_NOT_FOUND");
    }

    @Test
    void getSlots_unknownVenue_returns404() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/venues/" + UUID.randomUUID() + "/slots",
                HttpMethod.GET, HttpEntity.EMPTY, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("VENUE_NOT_FOUND");
    }

    @Test
    void getSlots_responseIncludesEndTime() {
        HttpEntity<CreateVenueRequest> createReq = new HttpEntity<>(sampleVenue("EndTime"), authHeaders("ADMIN"));
        ResponseEntity<VenueResponse> created = restTemplate.exchange(
                "/api/v1/admin/venues", HttpMethod.POST, createReq, VenueResponse.class);
        String venueId = created.getBody().venueId();

        GenerateSlotsRequest genReq = new GenerateSlotsRequest(
                LocalDate.of(2024, 3, 11), LocalDate.of(2024, 3, 11));
        restTemplate.exchange("/api/v1/admin/venues/" + venueId + "/slots/generate",
                HttpMethod.POST, new HttpEntity<>(genReq, authHeaders("ADMIN")),
                new ParameterizedTypeReference<List<SlotResponse>>() {});

        ResponseEntity<List<SlotResponse>> response = restTemplate.exchange(
                "/api/v1/venues/" + venueId + "/slots?date=2024-03-11",
                HttpMethod.GET, HttpEntity.EMPTY, new ParameterizedTypeReference<>() {});

        SlotResponse first = response.getBody().get(0);
        assertThat(first.endTime()).isEqualTo(first.startTime().plus(60, ChronoUnit.MINUTES));
    }
}
