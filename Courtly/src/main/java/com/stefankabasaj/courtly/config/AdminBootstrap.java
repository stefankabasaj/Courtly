package com.stefankabasaj.courtly.config;

import com.stefankabasaj.courtly.entity.User;
import com.stefankabasaj.courtly.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * Creates the admin account from ADMIN_EMAIL / ADMIN_PASSWORD at startup, so no
 * admin password ever has to live in the repository.
 *
 * - account doesn't exist          -> created as an active ADMIN
 * - account exists but is LOCKED   -> password set, unlocked, made ADMIN
 *   (that's the old seeded admin@courtly.com, locked by the V3 migration)
 * - account exists with a password -> left alone, a real password is never overwritten
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminBootstrap implements ApplicationRunner {

    /** Placeholder hash for accounts nobody can log into. Not BCrypt, so it never matches. */
    public static final String LOCKED_PASSWORD_HASH = "LOCKED";

    static final int MIN_PASSWORD_LENGTH = 12;

    private final AdminProperties adminProperties;
    private final UserRepository  userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String email    = adminProperties.email();
        String password = adminProperties.password();

        if (!StringUtils.hasText(email) || !StringUtils.hasText(password)) {
            if (!userRepository.existsByRoleAndActiveTrue(User.Role.ADMIN)) {
                log.warn("No active admin account. Set ADMIN_EMAIL and ADMIN_PASSWORD to create one.");
            }
            return;
        }

        // fail fast - better a startup error than an admin account with a weak password
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                    "ADMIN_PASSWORD must be at least " + MIN_PASSWORD_LENGTH + " characters.");
        }

        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        userRepository.findByEmail(normalizedEmail).ifPresentOrElse(
                user -> unlockIfLocked(user, password),
                () -> createAdmin(normalizedEmail, password));
    }

    private void createAdmin(String email, String password) {
        userRepository.save(User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(password))
                .role(User.Role.ADMIN)
                .build());
        log.info("Admin account created email={}", email);
    }

    private void unlockIfLocked(User user, String password) {
        if (!LOCKED_PASSWORD_HASH.equals(user.getPasswordHash())) {
            if (user.getRole() != User.Role.ADMIN) {
                log.warn("ADMIN_EMAIL {} belongs to a non-admin account; leaving it unchanged.", user.getEmail());
            }
            return;
        }
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRole(User.Role.ADMIN);
        user.setActive(true);
        userRepository.save(user);
        log.info("Admin account unlocked email={}", user.getEmail());
    }
}
