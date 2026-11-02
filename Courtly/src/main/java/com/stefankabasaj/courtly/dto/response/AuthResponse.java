package com.stefankabasaj.courtly.dto.response;

import java.time.Instant;

public record AuthResponse(
        String token,
        String type,
        Instant expiresAt,   // UTC, e.g. 2026-11-03T13:48:00Z - log the user out after this
        String email,
        String role
) {
    public static AuthResponse of(String token, Instant expiresAt, String email, String role) {
        return new AuthResponse(token, "Bearer", expiresAt, email, role);
    }
}
