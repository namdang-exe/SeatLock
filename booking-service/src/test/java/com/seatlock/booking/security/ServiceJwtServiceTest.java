package com.seatlock.booking.security;

import com.seatlock.common.security.JwtUtils;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceJwtServiceTest {

    private static final String SECRET = "seatlock-test-service-jwt-secret-change-in-prod";
    private static final String ISSUER = "seatlock-internal";
    private static final String SUBJECT = "booking-service";

    private ServiceJwtService serviceJwtService;
    private JwtUtils jwtUtils;

    @BeforeEach
    void setUp() {
        serviceJwtService = new ServiceJwtService(SECRET, ISSUER, SUBJECT, 5L);
        jwtUtils = new JwtUtils(SECRET);
    }

    @Test
    void generateToken_subjectIsBookingService() {
        String token = serviceJwtService.generateToken();
        Claims claims = jwtUtils.parseAndValidate(token);
        assertThat(claims.getSubject()).isEqualTo(SUBJECT);
    }

    @Test
    void generateToken_issuerIsInternal() {
        String token = serviceJwtService.generateToken();
        Claims claims = jwtUtils.parseAndValidate(token);
        assertThat(claims.getIssuer()).isEqualTo(ISSUER);
    }

    @Test
    void generateToken_expiresInFiveMinutes() {
        Instant before = Instant.now();
        String token = serviceJwtService.generateToken();
        Claims claims = jwtUtils.parseAndValidate(token);
        Instant expiry = claims.getExpiration().toInstant();
        assertThat(expiry).isAfter(before.plusSeconds(290));
        assertThat(expiry).isBefore(before.plusSeconds(310));
    }

    @Test
    void generateToken_signedWithSharedSecret() {
        String token = serviceJwtService.generateToken();
        // parseAndValidate throws JwtException if signature is wrong; asserting no exception
        assertThat(jwtUtils.parseAndValidate(token)).isNotNull();
    }
}
