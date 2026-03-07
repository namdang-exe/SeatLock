package com.seatlock.user.security;

import com.seatlock.common.security.JwtSigner;
import com.seatlock.common.security.JwtVerifier;
import com.seatlock.user.domain.User;
import io.jsonwebtoken.Claims;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class JwtService {

    private static final long EXPIRATION_HOURS = 24;

    private final JwtSigner signer;
    private final JwtVerifier verifier;

    public JwtService(JwtSigner signer, JwtVerifier verifier) {
        this.signer = signer;
        this.verifier = verifier;
    }

    public String issueToken(User user) {
        Instant expiry = Instant.now().plus(EXPIRATION_HOURS, ChronoUnit.HOURS);
        return signer.issue(user.getUserId(), user.getEmail(), user.getRole(), expiry);
    }

    public Instant expiresAt() {
        return Instant.now().plus(EXPIRATION_HOURS, ChronoUnit.HOURS);
    }

    public Claims parse(String token) {
        return verifier.parseAndValidate(token);
    }
}
