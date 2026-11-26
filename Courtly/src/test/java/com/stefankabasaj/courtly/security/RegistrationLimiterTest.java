package com.stefankabasaj.courtly.security;

import com.stefankabasaj.courtly.config.RegistrationRateLimitProperties;
import com.stefankabasaj.courtly.exception.TooManyRequestsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RegistrationLimiter")
class RegistrationLimiterTest {

    private static final String IP = "203.0.113.7";

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneOffset.UTC);
    private final RegistrationLimiter limiter = new RegistrationLimiter(
            new RegistrationRateLimitProperties(3, Duration.ofHours(1)),
            new InMemoryFailureCounter(clock));

    @Test
    @DisplayName("allows up to the limit, then 429 with a Retry-After")
    void blocksAfterLimit() {
        for (int i = 0; i < 3; i++) {
            assertThatNoException().isThrownBy(() -> limiter.checkAndRecord(IP));
        }
        assertThatThrownBy(() -> limiter.checkAndRecord(IP))
                .isInstanceOf(TooManyRequestsException.class)
                .satisfies(e -> assertThat(((TooManyRequestsException) e).getRetryAfterSeconds())
                        .isEqualTo(3600));
    }

    @Test
    @DisplayName("other IPs are not affected")
    void perIp() {
        for (int i = 0; i < 3; i++) limiter.checkAndRecord(IP);
        assertThatNoException().isThrownBy(() -> limiter.checkAndRecord("198.51.100.1"));
    }
}
