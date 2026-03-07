package com.seatlock.common.security;

import java.time.Instant;
import java.util.UUID;

public interface JwtSigner {

    /**
     * Issue a signed JWT for the given user identity.
     *
     * @param userId  the user's UUID (stored as the "userId" claim)
     * @param email   the user's email (stored as the subject)
     * @param role    the user's role (stored as the "role" claim)
     * @param expiry  the token expiry instant
     * @return compact serialised JWT string
     */
    String issue(UUID userId, String email, String role, Instant expiry);
}
