package com.seatlock.user.controller;

import com.seatlock.user.dto.LoginRequest;
import com.seatlock.user.dto.LoginResponse;
import com.seatlock.user.dto.RegisterRequest;
import com.seatlock.user.dto.RegisterResponse;
import com.seatlock.user.service.AuthenticationService;
import com.seatlock.user.service.UserRegistrationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final UserRegistrationService registrationService;
    private final AuthenticationService authenticationService;

    public AuthController(UserRegistrationService registrationService,
                          AuthenticationService authenticationService) {
        this.registrationService = registrationService;
        this.authenticationService = authenticationService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
        return registrationService.register(request);
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authenticationService.login(request);
    }
}