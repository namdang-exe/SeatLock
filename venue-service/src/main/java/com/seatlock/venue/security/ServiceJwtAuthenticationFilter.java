package com.seatlock.venue.security;

import com.seatlock.common.security.JwtUtils;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class ServiceJwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String EXPECTED_SUBJECT = "booking-service";
    private static final String INTERNAL_PATH_PREFIX = "/api/v1/internal/";

    private final JwtUtils serviceJwtUtils;

    public ServiceJwtAuthenticationFilter(@Value("${seatlock.service-jwt.secret}") String secret) {
        this.serviceJwtUtils = new JwtUtils(secret);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(INTERNAL_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        String token = header.substring(7);
        try {
            Claims claims = serviceJwtUtils.parseAndValidate(token);
            if (!EXPECTED_SUBJECT.equals(claims.getSubject())) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
            var auth = new UsernamePasswordAuthenticationToken(
                    claims.getSubject(), null, List.of(new SimpleGrantedAuthority("ROLE_SERVICE")));
            SecurityContextHolder.getContext().setAuthentication(auth);
        } catch (JwtException e) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        chain.doFilter(request, response);
    }
}
