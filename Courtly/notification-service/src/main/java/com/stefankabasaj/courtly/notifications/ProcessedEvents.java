package com.stefankabasaj.courtly.notifications;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Which events have already been emailed. Kafka delivers at least once, and
 * the API's outbox can send the same event twice (crash between the Kafka ack
 * and marking the row), so the same eventId can arrive more than once.
 *
 * A week is far longer than any redelivery takes. After that the key expires
 * by itself.
 */
@Component
public class ProcessedEvents {

    static final String   KEY_PREFIX = "courtly:notifications:sent:";
    static final Duration KEEP       = Duration.ofDays(7);

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
