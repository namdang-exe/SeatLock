package com.seatlock.user.security;

import com.seatlock.common.security.JwtSigner;
import io.jsonwebtoken.Jwts;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * RS256 (RSA-SHA256) signer — used in production by user-service only.
 * Holds only the private key; signing is isolated to this single service.
 */
public class Rs256JwtSigner implements JwtSigner {

    private final PrivateKey privateKey;

    public Rs256JwtSigner(String privateKeyPem) {
        this.privateKey = parsePrivateKey(privateKeyPem);
    }

    @Override
    public String issue(UUID userId, String email, String role, Instant expiry) {
        return Jwts.builder()
                .subject(email)
                .claim("userId", userId != null ? userId.toString() : null)
                .claim("role", role)
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(expiry))
                .signWith(privateKey)
                .compact();
    }

    private static PrivateKey parsePrivateKey(String pem) {
        try {
            String stripped = pem
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] decoded = Base64.getDecoder().decode(stripped);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(decoded));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid RS256 private key PEM", e);
        }
    }
}
