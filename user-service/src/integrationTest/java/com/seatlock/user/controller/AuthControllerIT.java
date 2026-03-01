package com.seatlock.user.controller;

import com.seatlock.user.AbstractIntegrationTest;
import com.seatlock.user.dto.LoginRequest;
import com.seatlock.user.dto.LoginResponse;
import com.seatlock.user.dto.RegisterRequest;
import com.seatlock.user.dto.RegisterResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class AuthControllerIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void register_happyPath_returns201WithUserIdAndEmail() {
        RegisterRequest request = new RegisterRequest("alice@example.com", "password123", null);

        ResponseEntity<RegisterResponse> response = restTemplate.postForEntity(
                "/api/v1/auth/register", request, RegisterResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().email()).isEqualTo("alice@example.com");
        assertThat(response.getBody().userId()).isNotNull();
    }

    @Test
    void register_duplicateEmail_returns409() {
        RegisterRequest request = new RegisterRequest("duplicate@example.com", "password123", null);
        restTemplate.postForEntity("/api/v1/auth/register", request, String.class);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/register", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("EMAIL_ALREADY_EXISTS");
    }

    @Test
    void login_happyPath_returns200WithToken() {
        RegisterRequest reg = new RegisterRequest("bob@example.com", "password123", null);
        restTemplate.postForEntity("/api/v1/auth/register", reg, RegisterResponse.class);

        LoginRequest login = new LoginRequest("bob@example.com", "password123");
        ResponseEntity<LoginResponse> response = restTemplate.postForEntity(
                "/api/v1/auth/login", login, LoginResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().token()).isNotBlank();
        assertThat(response.getBody().expiresAt()).isNotNull();
    }

    @Test
    void login_wrongPassword_returns401() {
        RegisterRequest reg = new RegisterRequest("charlie@example.com", "password123", null);
        restTemplate.postForEntity("/api/v1/auth/register", reg, RegisterResponse.class);

        LoginRequest login = new LoginRequest("charlie@example.com", "wrongpassword");
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/login", login, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("INVALID_CREDENTIALS");
    }

    @Test
    void protectedEndpoint_withoutJwt_returns401() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/protected-does-not-exist", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void login_tokenContainsCorrectClaims() {
        RegisterRequest reg = new RegisterRequest("diana@example.com", "password123", null);
        restTemplate.postForEntity("/api/v1/auth/register", reg, RegisterResponse.class);

        LoginRequest login = new LoginRequest("diana@example.com", "password123");
        ResponseEntity<LoginResponse> response = restTemplate.postForEntity(
                "/api/v1/auth/login", login, LoginResponse.class);

        String token = response.getBody().token();
        // Decode the payload (middle part of JWT) without verifying signature
        String payload = new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        assertThat(payload).contains("diana@example.com");
        assertThat(payload).contains("USER");
        assertThat(payload).contains("userId");
    }
}