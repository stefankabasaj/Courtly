package com.stefankabasaj.courtly.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
    name = "users",
    schema = "courtly",
    uniqueConstraints = @UniqueConstraint(name = "uq_users_email", columnNames = "email")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    // always lowercased in AuthService before we touch the DB, otherwise
    // Stefan@... and stefan@... end up as two different accounts
    @Column(nullable = false, unique = true, length = 255)
    private String email;

    // BCrypt hash, never the raw password. named *Hash on purpose so nobody logs it by accident
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    // STRING not ORDINAL - if I reorder this enum later, ORDINAL would quietly
    // remap every existing row to the wrong value
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    // soft delete. deleting a user would orphan their bookings, so we just flip this
    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public enum Role {
        USER,
        ADMIN
    }
}
