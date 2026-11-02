package com.stefankabasaj.courtly.service;

import com.stefankabasaj.courtly.dto.request.LoginRequest;
import com.stefankabasaj.courtly.dto.request.RegisterRequest;
import com.stefankabasaj.courtly.dto.response.AuthResponse;
import com.stefankabasaj.courtly.dto.response.UserResponse;
import com.stefankabasaj.courtly.entity.User;
import com.stefankabasaj.courtly.exception.BusinessException;
import com.stefankabasaj.courtly.exception.ConflictException;
import com.stefankabasaj.courtly.exception.ResourceNotFoundException;
import com.stefankabasaj.courtly.repository.UserRepository;
import com.stefankabasaj.courtly.security.IssuedToken;
import com.stefankabasaj.courtly.security.JwtService;
import com.stefankabasaj.courtly.security.LoginAttemptService;
import com.stefankabasaj.courtly.validation.MaxBytesValidator;
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

    // BCrypt can't hash more than this, and newer Spring Security throws instead of truncating
    private static final int BCRYPT_MAX_BYTES = 72;

    private final UserRepository      userRepository;
    private final PasswordEncoder     passwordEncoder;
    private final JwtService          jwtService;
    private final LoginAttemptService loginAttempts;

    @Transactional
    public UserResponse register(RegisterRequest request) {
        String email = normalize(request.email());
        log.info("Registering user email={}", email);

        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("An account with this email already exists.");
        }

        User user = User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(request.password()))
                .role(User.Role.USER)
                .build();

        // flush so createdAt (set by Hibernate at the INSERT) is in the response
        User saved = userRepository.saveAndFlush(user);
        log.info("User registered id={}", saved.getId());
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request, String clientIp) {
        String email = normalize(request.email());
        log.debug("Login attempt email={} ip={}", email, clientIp);

        // before touching the DB or BCrypt, so a blocked attacker costs us nothing
        loginAttempts.checkAllowed(email, clientIp);

        // unknown email and wrong password count the same, so the limit doesn't
        // reveal which emails have accounts
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null || !passwordMatches(request.password(), user)) {
            loginAttempts.recordFailure(email, clientIp);
            throw new BadCredentialsException("Invalid email or password");
        }
        loginAttempts.recordSuccess(email);

        // after the password check, so only the account owner learns it was deactivated
        if (!user.isActive()) {
            throw new BusinessException("This account has been deactivated. Please contact support.");
        }

        IssuedToken token = jwtService.issueToken(user.getId(), user.getEmail(), user.getRole().name());
        log.info("Login successful userId={}", user.getId());
        return AuthResponse.of(token.token(), token.expiresAt(), user.getEmail(), user.getRole().name());
    }

    // from the DB, not the token - so a deactivated account shows up as such
    @Transactional(readOnly = true)
    public UserResponse currentUser(UUID userId) {
        return userRepository.findById(userId)
                .map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
    }

    // a password BCrypt can't even hash can't be anyone's password - just a failed login, not a 500
    private boolean passwordMatches(String raw, User user) {
        return MaxBytesValidator.utf8Length(raw) <= BCRYPT_MAX_BYTES
                && passwordEncoder.matches(raw, user.getPasswordHash());
    }

    // normalize so casing / stray spaces don't create duplicate accounts
    // Locale.ROOT: the default locale breaks lowercasing (Turkish "I" becomes a dotless i)
    private String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private UserResponse toResponse(User user) {
        return new UserResponse(
                user.getId(), user.getEmail(),
                user.getRole().name(), user.isActive(), user.getCreatedAt());
    }
}
