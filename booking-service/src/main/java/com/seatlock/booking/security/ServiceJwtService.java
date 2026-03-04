package com.seatlock.booking.security;

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
public class ServiceJwtService {

    private final SecretKey key;
    private final String issuer;
    private final String subject;
    private final long ttlMinutes;

    public ServiceJwtService(
            @Value("${seatlock.service-jwt.secret}") String secret,
            @Value("${seatlock.service-jwt.issuer}") String issuer,
            @Value("${seatlock.service-jwt.subject}") String subject,
            @Value("${seatlock.service-jwt.ttl-minutes}") long ttlMinutes) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.issuer = issuer;
        this.subject = subject;
        this.ttlMinutes = ttlMinutes;
    }

    public String generateToken() {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(subject)
                .issuer(issuer)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttlMinutes, ChronoUnit.MINUTES)))
                .signWith(key)
                .compact();
    }
}
