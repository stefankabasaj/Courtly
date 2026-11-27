package com.stefankabasaj.courtly.security;

import com.stefankabasaj.courtly.config.RegistrationRateLimitProperties;
import com.stefankabasaj.courtly.exception.TooManyRequestsException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Caps registrations per client IP. Every registration costs a BCrypt hash
 * (~250ms of CPU), so without a cap one client could keep the instances busy
 * hashing - or fill the users table with junk accounts.
 *
 * Counts attempts, not only successes: a flood of duplicate emails costs a DB
 * query each and is just as much someone scripting it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RegistrationLimiter {

    private final RegistrationRateLimitProperties limits;
    private final FailureCounter                  counter;

    public void checkAndRecord(String ip) {
        String key = "register-ip:" + ip;
        if (counter.count(key) >= limits.maxPerIp()) {
            long retryAfter = Math.max(1, counter.timeLeft(key).toSeconds());
            log.warn("Registration blocked ip={} retryAfter={}s", ip, retryAfter);
            throw new TooManyRequestsException(
                    "Too many accounts created from this address. Try again later.", retryAfter);
        }
        counter.increment(key, limits.window());
    }
}
