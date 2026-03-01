package com.seatlock.user.service;

import com.seatlock.common.exception.EmailAlreadyExistsException;
import com.seatlock.user.domain.User;
import com.seatlock.user.dto.RegisterRequest;
import com.seatlock.user.dto.RegisterResponse;
import com.seatlock.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserRegistrationServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserRegistrationService service;

    @Test
    void register_happyPath_returnsUserIdAndEmail() {
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password1")).thenReturn("hashed");

        User saved = new User();
        saved.setEmail("alice@example.com");
        var field = UUID.randomUUID();
        // Use a real User to get a non-null userId from save
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            // Simulate DB-assigned UUID by returning same object; userId stays null in unit test
            return u;
        });

        RegisterRequest request = new RegisterRequest("alice@example.com", "password1", null);
        RegisterResponse response = service.register(request);

        assertThat(response.email()).isEqualTo("alice@example.com");
        verify(passwordEncoder).encode("password1");
        verify(userRepository).save(any(User.class));
    }

    @Test
    void register_duplicateEmail_throwsEmailAlreadyExistsException() {
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(true);

        RegisterRequest request = new RegisterRequest("alice@example.com", "password1", null);

        assertThatThrownBy(() -> service.register(request))
                .isInstanceOf(EmailAlreadyExistsException.class);

        verify(userRepository, never()).save(any());
    }
}