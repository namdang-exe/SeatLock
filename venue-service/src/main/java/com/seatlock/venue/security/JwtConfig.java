package com.seatlock.venue.security;

import com.seatlock.common.security.Hs256JwtProvider;
import com.seatlock.common.security.JwtVerifier;
import com.seatlock.common.security.Rs256JwtVerifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JwtConfig {

    @Bean
    @ConditionalOnProperty(name = "seatlock.jwt.algorithm", havingValue = "HS256", matchIfMissing = true)
    public Hs256JwtProvider hs256JwtProvider(@Value("${seatlock.jwt.secret}") String secret) {
        return new Hs256JwtProvider(secret);
    }

    @Bean
    @ConditionalOnProperty(name = "seatlock.jwt.algorithm", havingValue = "RS256")
    public JwtVerifier rs256JwtVerifier(@Value("${seatlock.jwt.public-key}") String publicKeyPem) {
        return new Rs256JwtVerifier(publicKeyPem);
    }
}
