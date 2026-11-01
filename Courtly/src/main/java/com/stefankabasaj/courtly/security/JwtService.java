package com.stefankabasaj.courtly.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class JwtService {

    private static final String CLAIM_USER_ID = "userId";
    private static final String CLAIM_ROLE    = "role";

    private final SecretKey secretKey;
    private final long      expirationMs;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expiration-ms}") long expirationMs
    ) {
        this.secretKey    = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    public String generateToken(UUID userId, String email, String role) {
        Date now    = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(email)
                .claim(CLAIM_USER_ID, userId.toString())
                .claim(CLAIM_ROLE, role)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(secretKey)
                .compact();
    }

    /**
     * Verifies the signature and expiry, and returns the user the token was issued for.
     * Empty for anything that isn't a valid token of ours - bad signature, expired,
     * malformed, or missing the claims we rely on. Parses the token once.
     */
    public Optional<AuthenticatedUser> parseToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String userId = claims.get(CLAIM_USER_ID, String.class);
            String role   = claims.get(CLAIM_ROLE, String.class);
            if (userId == null || role == null) {
                log.warn("JWT rejected: missing userId or role claim");
                return Optional.empty();
            }

            return Optional.of(new AuthenticatedUser(
                    UUID.fromString(userId), claims.getSubject(), role));
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("JWT rejected: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
