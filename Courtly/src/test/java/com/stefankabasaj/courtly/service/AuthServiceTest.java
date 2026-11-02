package com.stefankabasaj.courtly.service;

import com.stefankabasaj.courtly.dto.request.LoginRequest;
import com.stefankabasaj.courtly.dto.request.RegisterRequest;
import com.stefankabasaj.courtly.dto.response.AuthResponse;
import com.stefankabasaj.courtly.dto.response.UserResponse;
import com.stefankabasaj.courtly.entity.User;
import com.stefankabasaj.courtly.exception.BusinessException;
import com.stefankabasaj.courtly.exception.ConflictException;
import com.stefankabasaj.courtly.repository.UserRepository;
import com.stefankabasaj.courtly.exception.TooManyRequestsException;
import com.stefankabasaj.courtly.security.IssuedToken;
import com.stefankabasaj.courtly.security.JwtService;
import com.stefankabasaj.courtly.security.LoginAttemptService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.springframework.test.context.ActiveProfiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
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
    @Mock LoginAttemptService loginAttempts;
    @Spy  SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    @InjectMocks AuthService authService;

    private static final String IP = "203.0.113.7";
    private static final Instant EXPIRES = Instant.parse("2030-06-01T10:00:00Z");

    @Test
    @DisplayName("register - success, returns USER role")
    void register_newEmail_success() {
        when(userRepository.existsByEmail("stefan@courtly.com")).thenReturn(false);
        when(passwordEncoder.encode("Password1!")).thenReturn("hashed");
        when(userRepository.saveAndFlush(any())).thenAnswer(inv -> {
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

        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("login - valid credentials return JWT token")
    void login_valid_returnsToken() {
        User user = User.builder()
                .id(UUID.randomUUID()).email("stefan@courtly.com")
                .passwordHash("hashed").role(User.Role.USER).active(true).build();

        when(userRepository.findByEmail("stefan@courtly.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Password1!", "hashed")).thenReturn(true);
        when(jwtService.issueToken(any(), any(), any())).thenReturn(new IssuedToken("jwt.token.here", EXPIRES));

        AuthResponse resp = authService.login(
                new LoginRequest("stefan@courtly.com", "Password1!"), IP);

        assertThat(resp.token()).isEqualTo("jwt.token.here");
        assertThat(resp.type()).isEqualTo("Bearer");
        assertThat(resp.expiresAt()).isEqualTo(EXPIRES);
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
                new LoginRequest("stefan@courtly.com", "wrong"), IP))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("login - unknown email throws BadCredentialsException")
    void login_unknownEmail_throws() {
        when(userRepository.findByEmail(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("nobody@courtly.com", "Password1!"), IP))
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
        when(jwtService.issueToken(any(), any(), any())).thenReturn(new IssuedToken("jwt.token.here", EXPIRES));

        AuthResponse resp = authService.login(
                new LoginRequest("  Stefan@Courtly.COM ", "Password1!"), IP);

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
                new LoginRequest("stefan@courtly.com", "Password1!"), IP))
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
                new LoginRequest("stefan@courtly.com", "wrong"), IP))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("login - blocked email/IP gets 429 before any password check")
    void login_blocked_throws() {
        doThrow(new TooManyRequestsException("Too many failed login attempts", 600))
                .when(loginAttempts).checkAllowed("stefan@courtly.com", IP);

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("stefan@courtly.com", "Password1!"), IP))
                .isInstanceOf(TooManyRequestsException.class);

        verifyNoInteractions(userRepository, passwordEncoder);
    }

    @Test
    @DisplayName("login - wrong password and unknown email are both counted as failures")
    void login_failuresAreRecorded() {
        User user = User.builder()
                .id(UUID.randomUUID()).email("stefan@courtly.com")
                .passwordHash("hashed").role(User.Role.USER).active(true).build();
        when(userRepository.findByEmail("stefan@courtly.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);
        when(userRepository.findByEmail("nobody@courtly.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("stefan@courtly.com", "wrong"), IP))
                .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> authService.login(new LoginRequest("nobody@courtly.com", "wrong"), IP))
                .isInstanceOf(BadCredentialsException.class);

        verify(loginAttempts).recordFailure("stefan@courtly.com", IP);
        verify(loginAttempts).recordFailure("nobody@courtly.com", IP);
        verify(loginAttempts, never()).recordSuccess(any());
    }

    @Test
    @DisplayName("login - success clears the email's failure counter")
    void login_successIsRecorded() {
        User user = User.builder()
                .id(UUID.randomUUID()).email("stefan@courtly.com")
                .passwordHash("hashed").role(User.Role.USER).active(true).build();
        when(userRepository.findByEmail("stefan@courtly.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Password1!", "hashed")).thenReturn(true);
        when(jwtService.issueToken(any(), any(), any())).thenReturn(new IssuedToken("jwt.token.here", EXPIRES));

        authService.login(new LoginRequest("stefan@courtly.com", "Password1!"), IP);

        verify(loginAttempts).recordSuccess("stefan@courtly.com");
        verify(loginAttempts, never()).recordFailure(any(), any());
    }

    @Test
    @DisplayName("login - password over BCrypt's 72 bytes is a failed login, not an exception")
    void login_passwordTooLongForBcrypt() {
        User user = User.builder()
                .id(UUID.randomUUID()).email("stefan@courtly.com")
                .passwordHash("hashed").role(User.Role.USER).active(true).build();
        when(userRepository.findByEmail("stefan@courtly.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("stefan@courtly.com", "ш".repeat(40)), IP))
                .isInstanceOf(BadCredentialsException.class);

        verify(passwordEncoder, never()).matches(any(), any());
        verify(loginAttempts).recordFailure("stefan@courtly.com", IP);
    }

    @Test
    @DisplayName("currentUser - comes from the DB, so a deactivated account shows as inactive")
    void currentUser_fromDb() {
        UUID id = UUID.randomUUID();
        User user = User.builder().id(id).email("stefan@courtly.com")
                .passwordHash("hashed").role(User.Role.USER).active(false).build();
        when(userRepository.findById(id)).thenReturn(Optional.of(user));

        UserResponse me = authService.currentUser(id);

        assertThat(me.email()).isEqualTo("stefan@courtly.com");
        assertThat(me.active()).isFalse();
    }

    @Test
    @DisplayName("login outcomes are counted - success, failed, blocked")
    void loginMetrics() {
        User user = User.builder()
                .id(UUID.randomUUID()).email("stefan@courtly.com")
                .passwordHash("hashed").role(User.Role.USER).active(true).build();
        when(userRepository.findByEmail("stefan@courtly.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Password1!", "hashed")).thenReturn(true);
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);
        when(jwtService.issueToken(any(), any(), any())).thenReturn(new IssuedToken("t", EXPIRES));

        authService.login(new LoginRequest("stefan@courtly.com", "Password1!"), IP);
        assertThatThrownBy(() -> authService.login(new LoginRequest("stefan@courtly.com", "wrong"), IP));
        doThrow(new TooManyRequestsException("blocked", 60)).when(loginAttempts).checkAllowed("stefan@courtly.com", IP);
        assertThatThrownBy(() -> authService.login(new LoginRequest("stefan@courtly.com", "wrong"), IP));

        assertThat(metrics.counter("courtly.logins", "result", "success").count()).isEqualTo(1.0);
        assertThat(metrics.counter("courtly.logins", "result", "failed").count()).isEqualTo(1.0);
        assertThat(metrics.counter("courtly.logins", "result", "blocked").count()).isEqualTo(1.0);
    }
}
