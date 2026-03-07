package com.seatlock.user.security;

import com.seatlock.common.security.Hs256JwtProvider;
import com.seatlock.common.security.JwtSigner;
import com.seatlock.common.security.JwtVerifier;
import com.seatlock.common.security.Rs256JwtVerifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JwtConfig {

    /**
     * Local / non-prod: single HS256 provider that satisfies both JwtSigner and JwtVerifier.
     * Active when seatlock.jwt.algorithm=HS256 (or unset — HS256 is the default).
     */
    @Bean
    @ConditionalOnProperty(name = "seatlock.jwt.algorithm", havingValue = "HS256", matchIfMissing = true)
    public Hs256JwtProvider hs256JwtProvider(@Value("${seatlock.jwt.secret}") String secret) {
        return new Hs256JwtProvider(secret);
    }

    /**
     * Production: RS256 signer — private key stays in user-service only.
     * Active when seatlock.jwt.algorithm=RS256.
     */
    @Bean
    @ConditionalOnProperty(name = "seatlock.jwt.algorithm", havingValue = "RS256")
    public JwtSigner rs256JwtSigner(@Value("${seatlock.jwt.private-key}") String privateKeyPem) {
        return new Rs256JwtSigner(privateKeyPem);
    }

    /**
     * Production: RS256 verifier — public key only, used by the auth filter in user-service.
     * Active when seatlock.jwt.algorithm=RS256.
     */
    @Bean
    @ConditionalOnProperty(name = "seatlock.jwt.algorithm", havingValue = "RS256")
    public JwtVerifier rs256JwtVerifier(@Value("${seatlock.jwt.public-key}") String publicKeyPem) {
        return new Rs256JwtVerifier(publicKeyPem);
    }
}
