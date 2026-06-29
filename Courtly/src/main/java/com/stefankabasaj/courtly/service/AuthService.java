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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository  userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService      jwtService;

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

        User saved = userRepository.save(user);
        log.info("User registered id={}", saved.getId());
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String email = normalize(request.email());
        log.info("Login attempt email={}", email);

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        if (!user.isActive()) {
            throw new BusinessException("This account has been deactivated. Please contact support.");
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid email or password");
        }

        String token = jwtService.generateToken(user.getId(), user.getEmail(), user.getRole().name());
        log.info("Login successful userId={}", user.getId());
        return AuthResponse.of(token, user.getEmail(), user.getRole().name());
    }

    // normalize so casing / stray spaces don't create duplicate accounts
    private String normalize(String email) {
        return email.toLowerCase().trim();
    }

    private UserResponse toResponse(User user) {
        return new UserResponse(
                user.getId(), user.getEmail(),
                user.getRole().name(), user.isActive(), user.getCreatedAt());
    }
}
