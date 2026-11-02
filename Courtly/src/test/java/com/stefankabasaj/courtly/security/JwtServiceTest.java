package com.stefankabasaj.courtly.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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
        String token = jwtService.issueToken(id, "stefan@courtly.com", "ADMIN").token();

        Optional<AuthenticatedUser> user = jwtService.parseToken(token);

        assertThat(user).contains(new AuthenticatedUser(id, "stefan@courtly.com", "ADMIN"));
    }

    @Test
    @DisplayName("an expired token is rejected")
    void expired() {
        JwtService shortLived = new JwtService(SECRET, -1_000);
        String token = shortLived.issueToken(UUID.randomUUID(), "stefan@courtly.com", "USER").token();

        assertThat(jwtService.parseToken(token)).isEmpty();
    }

    @Test
    @DisplayName("a token signed with another key is rejected")
    void wrongSignature() {
        String token = new JwtService(OTHER_SECRET, 3_600_000)
                .issueToken(UUID.randomUUID(), "stefan@courtly.com", "USER").token();

        assertThat(jwtService.parseToken(token)).isEmpty();
    }

    @Test
    @DisplayName("garbage is rejected without throwing")
    void garbage() {
        assertThat(jwtService.parseToken("not.a.jwt")).isEmpty();
        assertThat(jwtService.parseToken("")).isEmpty();
    }

    @Test
    @DisplayName("expiresAt matches the exp claim, an hour out")
    void expiresAt() {
        Instant before = Instant.now();
        IssuedToken issued = jwtService.issueToken(UUID.randomUUID(), "stefan@courtly.com", "USER");

        assertThat(issued.expiresAt())
                .isAfter(before.plusSeconds(3_598))
                .isBefore(before.plusSeconds(3_602));
    }
}
