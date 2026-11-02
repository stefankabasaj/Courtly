package com.stefankabasaj.courtly.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stefankabasaj.courtly.exception.BusinessException;
import com.stefankabasaj.courtly.exception.ConflictException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Idempotency keys, the way Stripe does them. The client sends a key it made
 * up (a UUID) with a request that must not happen twice - a payment. If the
 * same request comes again with the same key (a double click, a retry after a
 * timeout when the client never saw the answer), it gets the first answer back
 * instead of a second payment attempt.
 *
 * In Redis, per user:
 *   - claimed with SET NX before running, so two copies arriving at once can't both run
 *   - the claim expires after a minute, so a crash mid-request doesn't block the key for a day
 *   - the result is kept 24h after it's done
 *   - if the request fails, the claim is dropped and the same key can be retried
 *
 * Same key, different request (another booking) is refused rather than
 * replayed - that's a client bug, and replaying would hide it.
 */
@Component
@RequiredArgsConstructor
public class IdempotencyService {

    static final String   KEY_PREFIX      = "courtly:idempotency:";
    static final Duration IN_PROGRESS_TTL = Duration.ofMinutes(1);
    static final Duration RESULT_TTL      = Duration.ofHours(24);

    private static final Pattern VALID_KEY = Pattern.compile("[A-Za-z0-9_-]{8,100}");

    private final StringRedisTemplate redis;
    private final ObjectMapper        objectMapper;

    public record Outcome<T>(T result, boolean replayed) {}

    // what's stored under the key: which request it was for, and its result once there is one
    record Entry(String fingerprint, String result) {
        boolean done() {
            return result != null;
        }
    }

    /**
     * @param scope       whose key this is (e.g. "payment:<userId>") - keys never collide across users
     * @param fingerprint what the request was, to tell a real repeat from a reused key
     */
    public <T> Outcome<T> execute(String scope, String key, String fingerprint, Class<T> type, Supplier<T> action) {
        if (key == null || !VALID_KEY.matcher(key).matches()) {
            throw new BusinessException("Idempotency-Key must be 8-100 characters: letters, digits, '-' or '_'.");
        }
        String redisKey = KEY_PREFIX + scope + ":" + key;

        Boolean claimed = redis.opsForValue().setIfAbsent(
                redisKey, toJson(new Entry(fingerprint, null)), IN_PROGRESS_TTL);
        if (!Boolean.TRUE.equals(claimed)) {
            return replay(redisKey, fingerprint, type);
        }

        T result;
        try {
            result = action.get();
        } catch (RuntimeException e) {
            // the request didn't happen (it rolled back), so let the client try again with the same key
            redis.delete(redisKey);
            throw e;
        }
        redis.opsForValue().set(redisKey, toJson(new Entry(fingerprint, toJson(result))), RESULT_TTL);
        return new Outcome<>(result, false);
    }

    private <T> Outcome<T> replay(String redisKey, String fingerprint, Class<T> type) {
        String stored = redis.opsForValue().get(redisKey);
        if (stored == null) {
            // the claim expired between our SET NX and this GET - rare, and safe to just retry
            throw new ConflictException("Please retry this request.");
        }
        Entry entry = fromJson(stored, Entry.class);
        if (!entry.fingerprint().equals(fingerprint)) {
            throw new BusinessException("This Idempotency-Key was already used for a different request.");
        }
        if (!entry.done()) {
            throw new ConflictException("A request with this Idempotency-Key is still being processed.");
        }
        return new Outcome<>(fromJson(entry.result(), type), true);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize " + value.getClass().getSimpleName(), e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt idempotency entry", e);
        }
    }
}
