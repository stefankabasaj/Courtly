package com.stefankabasaj.courtly.security;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * One Redis key per email / IP, holding the failure count. The key expires
 * when the window ends, so there's nothing to clean up and every instance
 * sees the same numbers.
 */
@Component
@RequiredArgsConstructor
public class RedisFailureCounter implements FailureCounter {

    static final String KEY_PREFIX = "courtly:login-failures:";

    // INCR and PEXPIRE in one step. done separately, a crash between the two
    // would leave a counter that never expires - a permanent lockout
    private static final RedisScript<Long> INCREMENT = RedisScript.of("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    private final StringRedisTemplate redis;

    @Override
    public long increment(String key, Duration window) {
        Long count = redis.execute(INCREMENT, List.of(KEY_PREFIX + key), String.valueOf(window.toMillis()));
        return count != null ? count : 0;
    }

    @Override
    public long count(String key) {
        String value = redis.opsForValue().get(KEY_PREFIX + key);
        return value != null ? Long.parseLong(value) : 0;
    }

    @Override
    public Duration timeLeft(String key) {
        // getExpire returns -2 for a missing key and -1 for one without a TTL
        Long millis = redis.getExpire(KEY_PREFIX + key, TimeUnit.MILLISECONDS);
        return millis != null && millis > 0 ? Duration.ofMillis(millis) : Duration.ZERO;
    }

    @Override
    public void reset(String key) {
        redis.delete(KEY_PREFIX + key);
    }
}
