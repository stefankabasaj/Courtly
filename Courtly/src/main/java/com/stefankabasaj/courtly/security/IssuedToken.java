package com.stefankabasaj.courtly.security;

import java.time.Instant;

// a new JWT and when it stops working, so the client knows when to send the user back to login
public record IssuedToken(String token, Instant expiresAt) {}
