package com.stefankabasaj.courtly.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

// same contract as RedisFailureCounter, on a clock the test controls
class InMemoryFailureCounter implements FailureCounter {

    private record Window(Instant end, long count) {}

    private final Clock clock;
    private final Map<String, Window> windows = new HashMap<>();

    InMemoryFailureCounter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public long increment(String key, Duration window) {
        Window current = live(key);
        Window next = current == null
                ? new Window(clock.instant().plus(window), 1)
                : new Window(current.end(), current.count() + 1);
        windows.put(key, next);
        return next.count();
    }

    @Override
    public long count(String key) {
        Window w = live(key);
        return w == null ? 0 : w.count();
    }

    @Override
    public Duration timeLeft(String key) {
        Window w = live(key);
        return w == null ? Duration.ZERO : Duration.between(clock.instant(), w.end());
    }

    @Override
    public void reset(String key) {
        windows.remove(key);
    }

    private Window live(String key) {
        Window w = windows.get(key);
        return w != null && clock.instant().isBefore(w.end()) ? w : null;
    }
}
