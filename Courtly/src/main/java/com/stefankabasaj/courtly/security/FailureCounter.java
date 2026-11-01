package com.stefankabasaj.courtly.security;

import java.time.Duration;

/**
 * Counts failures per key in a fixed window that starts with the first failure.
 * Pulled out of LoginAttemptService so the counting can live in Redis (shared
 * by every app instance) while the rules stay testable without one.
 */
public interface FailureCounter {

    /** Adds a failure and returns the count in the current window. Starts a new window if none is running. */
    long increment(String key, Duration window);

    /** Failures in the current window, 0 if there is none. */
    long count(String key);

    /** How long until the current window ends, ZERO if there is none. */
    Duration timeLeft(String key);

    void reset(String key);
}
