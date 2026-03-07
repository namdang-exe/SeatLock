package com.seatlock.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Hs256JwtProviderTest {

    private static final String SECRET = "seatlock-test-jwt-secret-change-in-prod-32chars";

    private Hs256JwtProvider provider;

    @BeforeEach
    void setUp() {
        provider = new Hs256JwtProvider(SECRET);
    }

    @Test
    void issue_containsCorrectClaims() {
        UUID userId = UUID.randomUUID();
        Instant expiry = Instant.now().plus(1, ChronoUnit.HOURS);

        String token = provider.issue(userId, "alice@example.com", "USER", expiry);
        Claims claims = provider.parseAndValidate(token);

        assertThat(claims.getSubject()).isEqualTo("alice@example.com");
        assertThat(claims.get("userId", String.class)).isEqualTo(userId.toString());
        assertThat(claims.get("role", String.class)).isEqualTo("USER");
    }

    @Test
    void issue_tokenIsNotExpiredImmediately() {
        String token = provider.issue(UUID.randomUUID(), "bob@example.com", "ADMIN",
                Instant.now().plus(24, ChronoUnit.HOURS));
        Claims claims = provider.parseAndValidate(token);
        assertThat(claims.getExpiration()).isAfter(new java.util.Date());
    }

    @Test
    void parseAndValidate_expiredToken_throwsJwtException() {
        String token = provider.issue(UUID.randomUUID(), "carol@example.com", "USER",
                Instant.now().minus(1, ChronoUnit.SECONDS));
        assertThatThrownBy(() -> provider.parseAndValidate(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void parseAndValidate_wrongSecret_throwsJwtException() {
        Hs256JwtProvider other = new Hs256JwtProvider("completely-different-secret-value-32chars!!");
        String token = other.issue(UUID.randomUUID(), "dave@example.com", "USER",
                Instant.now().plus(1, ChronoUnit.HOURS));
        assertThatThrownBy(() -> provider.parseAndValidate(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void issue_nullUserId_gracefullyStoresNullClaim() {
        String token = provider.issue(null, "eve@example.com", "USER",
                Instant.now().plus(1, ChronoUnit.HOURS));
        Claims claims = provider.parseAndValidate(token);
        assertThat(claims.get("userId")).isNull();
    }
}
