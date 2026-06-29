package com.stefankabasaj.courtly.dto.response;

public record AuthResponse(
        String token,
        String type,
        String email,
        String role
) {
    public static AuthResponse of(String token, String email, String role) {
        return new AuthResponse(token, "Bearer", email, role);
    }
}
