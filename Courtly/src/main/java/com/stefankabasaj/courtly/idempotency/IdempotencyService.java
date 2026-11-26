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
 * Claims a request key in Redis and replays its completed response on retries.
 * Keys are scoped per caller and checked against a request fingerprint. An
 * unfinished claim expires after one minute; completed responses live for 24 hours.
 */
@Component
@RequiredArgsConstructor
public class IdempotencyService {

    static final String KEY_PREFIX = "courtly:idempotency:";
    static final Duration IN_PROGRESS_TTL = Duration.ofMinutes(1);
    static final Duration RESULT_TTL = Duration.ofHours(24);

    private static final Pattern VALID_KEY = Pattern.compile("[A-Za-z0-9_-]{8,100}");

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public record Outcome<T>(T result, boolean replayed) {}

    record Entry(String fingerprint, String result) {
        boolean isCompleted() {
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
            // Release the claim when the action fails so the caller can retry.
            redis.delete(redisKey);
            throw e;
        }
        redis.opsForValue().set(redisKey, toJson(new Entry(fingerprint, toJson(result))), RESULT_TTL);
        return new Outcome<>(result, false);
    }

    private <T> Outcome<T> replay(String redisKey, String fingerprint, Class<T> type) {
        String stored = redis.opsForValue().get(redisKey);
        if (stored == null) {
            // The claim may expire between SET NX and GET; ask the caller to retry.
            throw new ConflictException("Please retry this request.");
        }
        Entry entry = fromJson(stored, Entry.class);
        if (!entry.fingerprint().equals(fingerprint)) {
            throw new BusinessException("This Idempotency-Key was already used for a different request.");
        }
        if (!entry.isCompleted()) {
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
