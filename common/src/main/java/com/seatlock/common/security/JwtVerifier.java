package com.seatlock.common.security;

import io.jsonwebtoken.Claims;

public interface JwtVerifier {

    /**
     * Parse and validate a JWT. Throws {@link io.jsonwebtoken.JwtException} if the token
     * is invalid, expired, or the signature does not match.
     */
    Claims parseAndValidate(String token);
}
