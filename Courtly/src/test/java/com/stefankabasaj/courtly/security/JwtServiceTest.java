package com.stefankabasaj.courtly.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JwtService")
class JwtServiceTest {

    private static final String SECRET       = "test-secret-key-minimum-32-chars-for-hs256-algorithm";
    private static final String OTHER_SECRET = "another-secret-key-minimum-32-chars-for-hs256-alg";

    private final JwtService jwtService = new JwtService(SECRET, 3_600_000);

    @Test
    @DisplayName("a token we issued parses back to the same user")
    void roundTrip() {
        UUID id = UUID.randomUUID();
        String token = jwtService.generateToken(id, "stefan@courtly.com", "ADMIN");

        Optional<AuthenticatedUser> user = jwtService.parseToken(token);

        assertThat(user).contains(new AuthenticatedUser(id, "stefan@courtly.com", "ADMIN"));
    }

    @Test
    @DisplayName("an expired token is rejected")
    void expired() {
        JwtService shortLived = new JwtService(SECRET, -1_000);
        String token = shortLived.generateToken(UUID.randomUUID(), "stefan@courtly.com", "USER");

        assertThat(jwtService.parseToken(token)).isEmpty();
    }

    @Test
    @DisplayName("a token signed with another key is rejected")
    void wrongSignature() {
        String token = new JwtService(OTHER_SECRET, 3_600_000)
                .generateToken(UUID.randomUUID(), "stefan@courtly.com", "USER");

        assertThat(jwtService.parseToken(token)).isEmpty();
    }

    @Test
    @DisplayName("garbage is rejected without throwing")
    void garbage() {
        assertThat(jwtService.parseToken("not.a.jwt")).isEmpty();
        assertThat(jwtService.parseToken("")).isEmpty();
    }
}
