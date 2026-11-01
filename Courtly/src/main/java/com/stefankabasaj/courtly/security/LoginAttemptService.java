package com.stefankabasaj.courtly.security;

import com.stefankabasaj.courtly.config.LoginRateLimitProperties;
import com.stefankabasaj.courtly.exception.TooManyRequestsException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Counts failed logins per email and per client IP in a fixed time window.
 * Once a limit is hit, further attempts get 429 until the window runs out -
 * even with the right password, otherwise the limit would tell an attacker
 * when they guessed it.
 *
 * - per email: stops guessing one account's password
 * - per IP:    stops one client trying a few passwords on many accounts
 *
 * The counters are in Redis (RedisFailureCounter), so all app instances share
 * them - with two instances behind a load balancer, in-memory counters would
 * give an attacker twice the attempts.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginAttemptService {

    private final LoginRateLimitProperties limits;
    private final FailureCounter counter;

    /** Throws TooManyRequestsException if this email or IP is currently blocked. */
    public void checkAllowed(String email, String ip) {
        long retryAfter = Math.max(
                secondsBlocked(emailKey(email), limits.maxFailuresPerEmail()),
                secondsBlocked(ipKey(ip),       limits.maxFailuresPerIp()));

        if (retryAfter > 0) {
            log.warn("Login blocked email={} ip={} retryAfter={}s", email, ip, retryAfter);
            throw new TooManyRequestsException(
                    "Too many failed login attempts. Try again in " + minutes(retryAfter) + ".",
                    retryAfter);
        }
    }

    public void recordFailure(String email, String ip) {
        counter.increment(emailKey(email), limits.window());
        counter.increment(ipKey(ip),       limits.window());
    }

    /** A successful login clears the email's counter. The IP counter keeps running. */
    public void recordSuccess(String email) {
        counter.reset(emailKey(email));
    }

    private long secondsBlocked(String key, int maxFailures) {
        if (counter.count(key) < maxFailures) {
            return 0;
        }
        Duration left = counter.timeLeft(key);
        return left.isZero() ? 0 : Math.max(1, left.toSeconds());
    }

    private static String emailKey(String email) {
        return "email:" + email;
    }

    private static String ipKey(String ip) {
        return "ip:" + ip;
    }

    private static String minutes(long seconds) {
        long m = Math.max(1, (seconds + 59) / 60);
        return m == 1 ? "1 minute" : m + " minutes";
    }
}
