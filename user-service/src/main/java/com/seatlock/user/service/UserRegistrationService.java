package com.seatlock.user.service;

import com.seatlock.common.exception.EmailAlreadyExistsException;
import com.seatlock.user.domain.User;
import com.seatlock.user.dto.RegisterRequest;
import com.seatlock.user.dto.RegisterResponse;
import com.seatlock.user.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserRegistrationService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserRegistrationService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new EmailAlreadyExistsException("Email already registered: " + request.email());
        }
        User user = new User();
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setPhone(request.phone());
        User saved = userRepository.save(user);
        return new RegisterResponse(saved.getUserId(), saved.getEmail());
    }
}