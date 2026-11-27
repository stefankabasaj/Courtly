package com.stefankabasaj.courtly.service;

import com.stefankabasaj.courtly.dto.request.LoginRequest;
import com.stefankabasaj.courtly.dto.request.RegisterRequest;
import com.stefankabasaj.courtly.dto.response.AuthResponse;
import com.stefankabasaj.courtly.dto.response.UserResponse;
import com.stefankabasaj.courtly.entity.User;
import com.stefankabasaj.courtly.exception.BusinessException;
import com.stefankabasaj.courtly.exception.ConflictException;
import com.stefankabasaj.courtly.exception.ResourceNotFoundException;
import com.stefankabasaj.courtly.exception.TooManyRequestsException;
import com.stefankabasaj.courtly.repository.UserRepository;
import com.stefankabasaj.courtly.security.IssuedToken;
import com.stefankabasaj.courtly.security.JwtService;
import com.stefankabasaj.courtly.security.LoginAttemptService;
import com.stefankabasaj.courtly.validation.MaxBytesValidator;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    // BCrypt limits passwords by UTF-8 bytes, not characters.
    private static final int BCRYPT_MAX_BYTES = 72;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginAttemptService loginAttempts;
    private final MeterRegistry metrics;

    @Transactional
    public UserResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        log.info("Registering user email={}", email);

        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("An account with this email already exists.");
        }

        User user = User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(request.password()))
                .role(User.Role.USER)
                .build();

        // Populate the generated timestamp before building the response.
        User saved = userRepository.saveAndFlush(user);
        log.info("User registered id={}", saved.getId());
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request, String clientIp) {
        String email = normalizeEmail(request.email());
        log.debug("Login attempt email={} ip={}", email, clientIp);

        // Reject blocked attempts before the database lookup and password hash check.
        try {
            loginAttempts.checkAllowed(email, clientIp);
        } catch (TooManyRequestsException e) {
            countLogin("blocked");
            throw e;
        }

        // Use the same failure response and counters for unknown emails and wrong passwords.
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null || !passwordMatches(request.password(), user)) {
            loginAttempts.recordFailure(email, clientIp);
            countLogin("failed");
            throw new BadCredentialsException("Invalid email or password");
        }
        loginAttempts.recordSuccess(email);

        // Only reveal deactivation after verifying the password.
        if (!user.isActive()) {
            throw new BusinessException("This account has been deactivated. Please contact support.");
        }

        countLogin("success");
        IssuedToken token = jwtService.issueToken(user.getId(), user.getEmail(), user.getRole().name());
        log.info("Login successful userId={}", user.getId());
        return AuthResponse.of(token.token(), token.expiresAt(), user.getEmail(), user.getRole().name());
    }

    // Read current account status rather than the token's snapshot.
    @Transactional(readOnly = true)
    public UserResponse currentUser(UUID userId) {
        return userRepository.findById(userId)
                .map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
    }

    private void countLogin(String result) {
        metrics.counter("courtly.logins", "result", result).increment();
    }

    // Treat oversized passwords as invalid credentials instead of a hashing error.
    private boolean passwordMatches(String rawPassword, User user) {
        return MaxBytesValidator.utf8Length(rawPassword) <= BCRYPT_MAX_BYTES
                && passwordEncoder.matches(rawPassword, user.getPasswordHash());
    }

    // Locale.ROOT keeps email normalization independent of the server locale.
    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private UserResponse toResponse(User user) {
        return new UserResponse(
                user.getId(), user.getEmail(),
                user.getRole().name(), user.isActive(), user.getCreatedAt());
    }
}
