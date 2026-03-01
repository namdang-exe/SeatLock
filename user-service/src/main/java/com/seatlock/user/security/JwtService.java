package com.seatlock.user.security;

import com.seatlock.user.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

@Service
public class JwtService {

    private static final long EXPIRATION_HOURS = 24;

    private final SecretKey key;

    public JwtService(@Value("${seatlock.jwt.secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String issueToken(User user) {
        Instant now = Instant.now();
        Instant expiry = now.plus(EXPIRATION_HOURS, ChronoUnit.HOURS);
        return Jwts.builder()
                .subject(user.getEmail())
                .claim("userId", user.getUserId().toString())
                .claim("role", user.getRole())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key)
                .compact();
    }

    public Instant expiresAt() {
        return Instant.now().plus(EXPIRATION_HOURS, ChronoUnit.HOURS);
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}