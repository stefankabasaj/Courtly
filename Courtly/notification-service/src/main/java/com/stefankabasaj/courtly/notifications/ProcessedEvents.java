package com.stefankabasaj.courtly.notifications;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Tracks sent event IDs for seven days to suppress duplicate deliveries.
 * Replaying an event after its key expires can send the email again.
 */
@Component
public class ProcessedEvents {

    static final String KEY_PREFIX = "courtly:notifications:sent:";
    static final Duration KEEP = Duration.ofDays(7);

    private final StringRedisTemplate redis;

    public ProcessedEvents(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean alreadySent(UUID eventId) {
        return Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + eventId));
    }

    public void markSent(UUID eventId) {
        redis.opsForValue().set(KEY_PREFIX + eventId, "1", KEEP);
    }
}
