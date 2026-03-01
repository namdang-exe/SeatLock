package com.seatlock.user.security;

import com.seatlock.user.domain.User;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService("seatlock-test-jwt-secret-change-in-prod-32chars");
    }

    @Test
    void issueToken_containsCorrectClaims() {
        User user = new User();
        user.setEmail("alice@example.com");
        user.setRole("USER");
        // userId is null in a plain new User() — use reflection-free workaround via a saved UUID
        String token = jwtService.issueToken(user);

        Claims claims = jwtService.parse(token);

        assertThat(claims.getSubject()).isEqualTo("alice@example.com");
        assertThat(claims.get("role", String.class)).isEqualTo("USER");
    }

    @Test
    void issueToken_isNotExpiredImmediately() {
        User user = new User();
        user.setEmail("bob@example.com");
        user.setRole("ADMIN");

        String token = jwtService.issueToken(user);
        Claims claims = jwtService.parse(token);

        assertThat(claims.getExpiration()).isAfter(new java.util.Date());
    }
}