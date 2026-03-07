package com.seatlock.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * RS256 (RSA-SHA256) verifier — used in production.
 * Holds only the public key; cannot forge tokens.
 * Used by venue-service and booking-service.
 */
public class Rs256JwtVerifier implements JwtVerifier {

    private final PublicKey publicKey;

    public Rs256JwtVerifier(String publicKeyPem) {
        this.publicKey = parsePublicKey(publicKeyPem);
    }

    @Override
    public Claims parseAndValidate(String token) {
        return Jwts.parser()
                .verifyWith(publicKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private static PublicKey parsePublicKey(String pem) {
        try {
            String stripped = pem
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] decoded = Base64.getDecoder().decode(stripped);
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decoded));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid RS256 public key PEM", e);
        }
    }
}
