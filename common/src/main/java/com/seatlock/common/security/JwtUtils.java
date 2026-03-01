package com.seatlock.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

public class JwtUtils {

    private final SecretKey key;

    public JwtUtils(String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Parse and validate a JWT. Throws JwtException if the token is invalid or expired.
     */
    public Claims parseAndValidate(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    // --- User JWT helpers ---

    public Long getUserId(String token) {
        return parseAndValidate(token).get("userId", Long.class);
    }

    public String getUserEmail(String token) {
        return parseAndValidate(token).getSubject();
    }

    public String getRole(String token) {
        return parseAndValidate(token).get("role", String.class);
    }

    // --- Generic claim access (used for service JWTs) ---

    public String getSubject(String token) {
        return parseAndValidate(token).getSubject();
    }

    public String getIssuer(String token) {
        return parseAndValidate(token).getIssuer();
    }
}
