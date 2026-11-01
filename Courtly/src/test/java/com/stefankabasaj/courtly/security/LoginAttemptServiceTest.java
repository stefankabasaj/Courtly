package com.stefankabasaj.courtly.security;

import com.stefankabasaj.courtly.config.LoginRateLimitProperties;
import com.stefankabasaj.courtly.exception.TooManyRequestsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("LoginAttemptService")
class LoginAttemptServiceTest {

    private static final String EMAIL = "stefan@courtly.com";
    private static final String IP    = "203.0.113.7";

    // a clock the test can move forward
    private static class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-01T10:00:00Z");
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final TestClock clock = new TestClock();
    private final LoginAttemptService service = new LoginAttemptService(
            new LoginRateLimitProperties(3, 5, Duration.ofMinutes(15)),
            new InMemoryFailureCounter(clock));

    private void fail(String email, String ip, int times) {
        for (int i = 0; i < times; i++) service.recordFailure(email, ip);
    }

    @Test
    @DisplayName("allows attempts under the email limit")
    void underLimit() {
        fail(EMAIL, IP, 2);
        assertThatNoException().isThrownBy(() -> service.checkAllowed(EMAIL, IP));
    }

    @Test
    @DisplayName("blocks the email once its limit is reached, with a Retry-After")
    void emailLimit() {
        fail(EMAIL, IP, 3);

        assertThatThrownBy(() -> service.checkAllowed(EMAIL, IP))
                .isInstanceOf(TooManyRequestsException.class)
                .satisfies(e -> assertThat(((TooManyRequestsException) e).getRetryAfterSeconds())
                        .isEqualTo(15 * 60));
    }

    @Test
    @DisplayName("Retry-After counts down from the first failure, not the last")
    void retryAfterFromFirstFailure() {
        service.recordFailure(EMAIL, IP);
        clock.advance(Duration.ofMinutes(5));
        fail(EMAIL, IP, 2);

        assertThatThrownBy(() -> service.checkAllowed(EMAIL, IP))
                .satisfies(e -> assertThat(((TooManyRequestsException) e).getRetryAfterSeconds())
                        .isEqualTo(10 * 60));
    }

    @Test
    @DisplayName("the email stays blocked from another IP")
    void emailLimitAcrossIps() {
        fail(EMAIL, IP, 3);
        assertThatThrownBy(() -> service.checkAllowed(EMAIL, "198.51.100.1"))
                .isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    @DisplayName("blocks an IP that fails across many emails")
    void ipLimit() {
        for (int i = 0; i < 5; i++) service.recordFailure("user" + i + "@courtly.com", IP);

        assertThatThrownBy(() -> service.checkAllowed("fresh@courtly.com", IP))
                .isInstanceOf(TooManyRequestsException.class);
        assertThatNoException().isThrownBy(() -> service.checkAllowed("fresh@courtly.com", "198.51.100.1"));
    }

    @Test
    @DisplayName("unblocks when the window has passed")
    void windowExpires() {
        fail(EMAIL, IP, 3);
        clock.advance(Duration.ofMinutes(15));
        assertThatNoException().isThrownBy(() -> service.checkAllowed(EMAIL, IP));
    }

    @Test
    @DisplayName("a success resets the email's counter")
    void successResets() {
        fail(EMAIL, IP, 2);
        service.recordSuccess(EMAIL);
        fail(EMAIL, IP, 2);
        assertThatNoException().isThrownBy(() -> service.checkAllowed(EMAIL, IP));
    }
}
