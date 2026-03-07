package com.seatlock.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * HS256 (HMAC-SHA256) implementation — used in local / non-prod environments.
 * A single shared secret is used for both signing and verification.
 * Implements both {@link JwtSigner} and {@link JwtVerifier} so that Spring can
 * inject the same bean wherever either interface is required.
 */
public class Hs256JwtProvider implements JwtSigner, JwtVerifier {

    private final SecretKey key;

    public Hs256JwtProvider(String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String issue(UUID userId, String email, String role, Instant expiry) {
        return Jwts.builder()
                .subject(email)
                .claim("userId", userId != null ? userId.toString() : null)
                .claim("role", role)
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(expiry))
                .signWith(key)
                .compact();
    }

    @Override
    public Claims parseAndValidate(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
