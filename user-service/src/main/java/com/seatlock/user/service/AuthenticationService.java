package com.seatlock.user.service;

import com.seatlock.common.exception.InvalidCredentialsException;
import com.seatlock.user.domain.User;
import com.seatlock.user.dto.LoginRequest;
import com.seatlock.user.dto.LoginResponse;
import com.seatlock.user.repository.UserRepository;
import com.seatlock.user.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthenticationService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthenticationService(UserRepository userRepository,
                                  PasswordEncoder passwordEncoder,
                                  JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(InvalidCredentialsException::new);
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        return new LoginResponse(jwtService.issueToken(user), jwtService.expiresAt());
    }
}