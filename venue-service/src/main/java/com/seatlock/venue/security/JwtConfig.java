package com.seatlock.venue.security;

import com.seatlock.common.security.JwtUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JwtConfig {

    @Bean
    public JwtUtils jwtUtils(@Value("${seatlock.jwt.secret}") String secret) {
        return new JwtUtils(secret);
    }
}
