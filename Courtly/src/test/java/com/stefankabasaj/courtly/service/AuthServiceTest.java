package com.stefankabasaj.courtly.service;

import com.stefankabasaj.courtly.dto.request.LoginRequest;
import com.stefankabasaj.courtly.dto.request.RegisterRequest;
import com.stefankabasaj.courtly.dto.response.AuthResponse;
import com.stefankabasaj.courtly.dto.response.UserResponse;
import com.stefankabasaj.courtly.entity.User;
import com.stefankabasaj.courtly.exception.BusinessException;
import com.stefankabasaj.courtly.exception.ConflictException;
import com.stefankabasaj.courtly.repository.UserRepository;
import com.stefankabasaj.courtly.security.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.springframework.test.context.ActiveProfiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ActiveProfiles("test")
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService")
class AuthServiceTest {

    @Mock UserRepository  userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtService      jwtService;
    @InjectMocks AuthService authService;

    @Test
    @DisplayName("register - success, returns USER role")
    void register_newEmail_success() {
        when(userRepository.existsByEmail("stefan@courtly.com")).thenReturn(false);
        when(passwordEncoder.encode("Password1!")).thenReturn("hashed");
        when(userRepository.save(any())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });

        UserResponse resp = authService.register(
                new RegisterRequest("stefan@courtly.com", "Password1!"));

        assertThat(resp.email()).isEqualTo("stefan@courtly.com");
        assertThat(resp.role()).isEqualTo("USER");
    }

    @Test
    @DisplayName("register - duplicate email throws ConflictException")
    void register_duplicate_throws() {
        when(userRepository.existsByEmail("stefan@courtly.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("stefan@courtly.com", "Password1!")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already exists");

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("login - valid credentials return JWT token")
    void login_valid_returnsToken() {
        User user = User.builder()
                .id(UUID.randomUUID()).email("stefan@courtly.com")
                .passwordHash("hashed").role(User.Role.USER).active(true).build();

        when(userRepository.findByEmail("stefan@courtly.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Password1!", "hashed")).thenReturn(true);
        when(jwtService.generateToken(any(), any(), any())).thenReturn("jwt.token.here");

        AuthResponse resp = authService.login(
                new LoginRequest("stefan@courtly.com", "Password1!"));

        assertThat(resp.token()).isEqualTo("jwt.token.here");
        assertThat(resp.type()).isEqualTo("Bearer");
    }

    @Test
    @DisplayName("login - wrong password throws BadCredentialsException")
    void login_wrongPassword_throws() {
        User user = User.builder()
                .id(UUID.randomUUID()).email("stefan@courtly.com")
                .passwordHash("hashed").role(User.Role.USER).active(true).build();

        when(userRepository.findByEmail("stefan@courtly.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("stefan@courtly.com", "wrong")))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("login - unknown email throws BadCredentialsException")
    void login_unknownEmail_throws() {
        when(userRepository.findByEmail(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("nobody@courtly.com", "Password1!")))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("login - email is normalised (case, spaces)")
    void login_normalisesEmail() {
        User user = User.builder()
                .id(UUID.randomUUID()).email("stefan@courtly.com")
                .passwordHash("hashed").role(User.Role.USER).active(true).build();

        when(userRepository.findByEmail("stefan@courtly.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Password1!", "hashed")).thenReturn(true);
        when(jwtService.generateToken(any(), any(), any())).thenReturn("jwt.token.here");

        AuthResponse resp = authService.login(
                new LoginRequest("  Stefan@Courtly.COM ", "Password1!"));

        assertThat(resp.email()).isEqualTo("stefan@courtly.com");
    }

    @Test
    @DisplayName("login - deactivated account with the right password → BusinessException")
    void login_deactivated_throws() {
        User user = User.builder()
                .id(UUID.randomUUID()).email("stefan@courtly.com")
                .passwordHash("hashed").role(User.Role.USER).active(false).build();

        when(userRepository.findByEmail("stefan@courtly.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Password1!", "hashed")).thenReturn(true);

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("stefan@courtly.com", "Password1!")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("deactivated");
    }

    @Test
    @DisplayName("login - deactivated account with a wrong password looks like any other bad login")
    void login_deactivatedWrongPassword_doesNotRevealStatus() {
        User user = User.builder()
                .id(UUID.randomUUID()).email("stefan@courtly.com")
                .passwordHash("hashed").role(User.Role.USER).active(false).build();

        when(userRepository.findByEmail("stefan@courtly.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("stefan@courtly.com", "wrong")))
                .isInstanceOf(BadCredentialsException.class);
    }
}
