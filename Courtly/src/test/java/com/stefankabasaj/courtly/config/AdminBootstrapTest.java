package com.stefankabasaj.courtly.config;

import com.stefankabasaj.courtly.entity.User;
import com.stefankabasaj.courtly.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminBootstrap")
class AdminBootstrapTest {

    private static final String PASSWORD = "a-long-admin-password";

    @Mock UserRepository  userRepository;
    @Mock PasswordEncoder passwordEncoder;

    private AdminBootstrap bootstrap(String email, String password) {
        return new AdminBootstrap(new AdminProperties(email, password), userRepository, passwordEncoder);
    }

    private User existing(String hash, User.Role role, boolean active) {
        return User.builder().id(UUID.randomUUID()).email("admin@courtly.com")
                .passwordHash(hash).role(role).active(active).build();
    }

    @Test
    @DisplayName("creates the admin when the account doesn't exist")
    void createsAdmin() {
        when(userRepository.findByEmail("admin@courtly.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(PASSWORD)).thenReturn("hashed");

        bootstrap(" Admin@Courtly.com ", PASSWORD).run(null);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("admin@courtly.com");
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("hashed");
        assertThat(saved.getValue().getRole()).isEqualTo(User.Role.ADMIN);
        assertThat(saved.getValue().isActive()).isTrue();
    }

    @Test
    @DisplayName("unlocks the account locked by the V3 migration")
    void unlocksLocked() {
        User locked = existing(AdminBootstrap.LOCKED_PASSWORD_HASH, User.Role.ADMIN, false);
        when(userRepository.findByEmail("admin@courtly.com")).thenReturn(Optional.of(locked));
        when(passwordEncoder.encode(PASSWORD)).thenReturn("hashed");

        bootstrap("admin@courtly.com", PASSWORD).run(null);

        verify(userRepository).save(locked);
        assertThat(locked.getPasswordHash()).isEqualTo("hashed");
        assertThat(locked.isActive()).isTrue();
    }

    @Test
    @DisplayName("never overwrites a real password")
    void keepsExistingPassword() {
        User admin = existing("$2a$12$realhash", User.Role.ADMIN, true);
        when(userRepository.findByEmail("admin@courtly.com")).thenReturn(Optional.of(admin));

        bootstrap("admin@courtly.com", PASSWORD).run(null);

        verify(userRepository, never()).save(any());
        assertThat(admin.getPasswordHash()).isEqualTo("$2a$12$realhash");
    }

    @Test
    @DisplayName("does nothing when ADMIN_EMAIL / ADMIN_PASSWORD are not set")
    void notConfigured() {
        when(userRepository.existsByRoleAndActiveTrue(User.Role.ADMIN)).thenReturn(true);

        bootstrap("", "").run(null);

        verify(userRepository, never()).findByEmail(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("refuses a short admin password")
    void shortPassword() {
        assertThatThrownBy(() -> bootstrap("admin@courtly.com", "short").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least");
        verify(userRepository, never()).save(any());
    }
}
