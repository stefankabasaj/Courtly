package com.stefankabasaj.courtly.security;

import java.util.UUID;

// what we stash in the SecurityContext after the JWT filter runs.
// controllers grab it with @AuthenticationPrincipal, no DB hit
public record AuthenticatedUser(UUID userId, String email, String role) {

    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }

    // admins can access anything, everyone else only what they own
    public boolean canAccess(UUID ownerId) {
        return isAdmin() || userId.equals(ownerId);
    }
}
