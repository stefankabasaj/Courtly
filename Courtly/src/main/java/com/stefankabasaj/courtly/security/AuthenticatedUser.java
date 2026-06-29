package com.stefankabasaj.courtly.security;

import java.util.UUID;

// what we stash in the SecurityContext after the JWT filter runs.
// controllers grab it with @AuthenticationPrincipal, no DB hit
public record AuthenticatedUser(UUID userId, String email, String role) {}
