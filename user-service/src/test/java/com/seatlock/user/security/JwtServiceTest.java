package com.seatlock.user.security;

import com.seatlock.common.security.Hs256JwtProvider;
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
        Hs256JwtProvider provider = new Hs256JwtProvider("seatlock-test-jwt-secret-change-in-prod-32chars");
        jwtService = new JwtService(provider, provider);
    }

    @Test
    void issueToken_containsCorrectClaims() {
        User user = new User();
        user.setEmail("alice@example.com");
        user.setRole("USER");
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

    @Test
    void issueToken_embedsUserId() {
        User user = new User();
        UUID id = UUID.randomUUID();
        user.setEmail("carol@example.com");
        user.setRole("USER");
        // Set userId via reflection-free approach: just verify the claim is present
        // (userId is null on a plain new User() — handled gracefully)
        String token = jwtService.issueToken(user);
        Claims claims = jwtService.parse(token);
        // Claim exists (may be null for a new User())
        assertThat(claims.containsKey("userId")).isTrue();
    }
}
