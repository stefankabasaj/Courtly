package com.stefankabasaj.courtly.security;

import com.stefankabasaj.courtly.config.LoginRateLimitProperties;
import com.stefankabasaj.courtly.exception.TooManyRequestsException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Counts failed logins per email and per client IP in a fixed time window.
 * Once a limit is hit, further attempts get 429 until the window runs out -
 * even with the right password, otherwise the limit would tell an attacker
 * when they guessed it.
 *
 * - per email: stops guessing one account's password
 * - per IP:    stops one client trying a few passwords on many accounts
 *
 * Counters live in memory, so each instance counts on its own and a restart
 * resets them. Fine for a single instance; several instances behind a load
 * balancer would need a shared store (e.g. Redis).
 */
@Slf4j
@Component
public class LoginAttemptService {

    private final LoginRateLimitProperties limits;
    private final Clock clock;

    private final Map<String, Window> failuresByEmail = new ConcurrentHashMap<>();
    private final Map<String, Window> failuresByIp    = new ConcurrentHashMap<>();

    @Autowired
    public LoginAttemptService(LoginRateLimitProperties limits) {
        this(limits, Clock.systemUTC());
    }

    LoginAttemptService(LoginRateLimitProperties limits, Clock clock) {
        this.limits = limits;
        this.clock  = clock;
    }

    /** Throws TooManyRequestsException if this email or IP is currently blocked. */
    public void checkAllowed(String email, String ip) {
        Instant now = clock.instant();
        long retryAfter = Math.max(
                secondsBlocked(failuresByEmail.get(email), limits.maxFailuresPerEmail(), now),
                secondsBlocked(failuresByIp.get(ip),       limits.maxFailuresPerIp(),    now));

        if (retryAfter > 0) {
            log.warn("Login blocked email={} ip={} retryAfter={}s", email, ip, retryAfter);
            throw new TooManyRequestsException(
                    "Too many failed login attempts. Try again in " + minutes(retryAfter) + ".",
                    retryAfter);
        }
    }

    public void recordFailure(String email, String ip) {
        Instant now = clock.instant();
        failuresByEmail.compute(email, (k, w) -> increment(w, now));
        failuresByIp.compute(ip,       (k, w) -> increment(w, now));
    }

    /** A successful login clears the email's counter. The IP counter keeps running. */
    public void recordSuccess(String email) {
        failuresByEmail.remove(email);
    }

    // without this, every email or IP that ever failed once would stay in memory forever
    @Scheduled(fixedDelay = 10 * 60 * 1000)
    public void purgeExpired() {
        Instant now = clock.instant();
        failuresByEmail.values().removeIf(w -> w.isExpired(now, limits.window()));
        failuresByIp.values().removeIf(w -> w.isExpired(now, limits.window()));
    }

    private Window increment(Window current, Instant now) {
        if (current == null || current.isExpired(now, limits.window())) {
            return new Window(now, 1);
        }
        return new Window(current.start(), current.failures() + 1);
    }

    private long secondsBlocked(Window w, int maxFailures, Instant now) {
        if (w == null || w.failures() < maxFailures || w.isExpired(now, limits.window())) {
            return 0;
        }
        Instant unblockedAt = w.start().plus(limits.window());
        return Math.max(1, Duration.between(now, unblockedAt).toSeconds());
    }

    private static String minutes(long seconds) {
        long m = Math.max(1, (seconds + 59) / 60);
        return m == 1 ? "1 minute" : m + " minutes";
    }

    private record Window(Instant start, int failures) {
        boolean isExpired(Instant now, Duration window) {
            return !now.isBefore(start.plus(window));
        }
    }
}
