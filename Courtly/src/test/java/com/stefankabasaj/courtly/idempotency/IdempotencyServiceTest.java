package com.stefankabasaj.courtly.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stefankabasaj.courtly.exception.BusinessException;
import com.stefankabasaj.courtly.exception.ConflictException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// against a real Redis: SET NX and the TTLs are what this class relies on
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("IdempotencyService")
class IdempotencyServiceTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    private IdempotencyService service;
    private final AtomicInteger runs = new AtomicInteger();

    record Result(String id, String status) {}

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        service = new IdempotencyService(redis, new ObjectMapper());
    }

    private IdempotencyService.Outcome<Result> pay(String key, String fingerprint) {
        return service.execute("payment:user-1", key, fingerprint, Result.class, () -> {
            runs.incrementAndGet();
            return new Result("pay-" + runs.get(), "SUCCESS");
        });
    }

    @Test
    @DisplayName("the same request again gets the first result back, without running again")
    void replays() {
        var first  = pay("key-aaaaaaaa", "booking-1:CARD");
        var second = pay("key-aaaaaaaa", "booking-1:CARD");

        assertThat(runs.get()).isEqualTo(1);
        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.result()).isEqualTo(first.result());
    }

    @Test
    @DisplayName("different keys are different requests")
    void differentKeys() {
        pay("key-aaaaaaaa", "booking-1:CARD");
        pay("key-bbbbbbbb", "booking-1:CARD");

        assertThat(runs.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("the same key for a different request is refused, not replayed")
    void reusedForOtherRequest() {
        pay("key-aaaaaaaa", "booking-1:CARD");

        assertThatThrownBy(() -> pay("key-aaaaaaaa", "booking-2:CARD"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("different request");
        assertThat(runs.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a copy arriving while the first is still running gets 409, and doesn't run")
    void inProgress() {
        AtomicInteger inner = new AtomicInteger();
        service.execute("payment:user-1", "key-aaaaaaaa", "booking-1:CARD", Result.class, () -> {
            // the duplicate arrives mid-request
            assertThatThrownBy(() -> pay("key-aaaaaaaa", "booking-1:CARD"))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("still being processed");
            inner.incrementAndGet();
            return new Result("pay-1", "SUCCESS");
        });

        assertThat(inner.get()).isEqualTo(1);
        assertThat(runs.get()).isZero();
    }

    @Test
    @DisplayName("if the request fails, the key is free to retry")
    void failureReleasesKey() {
        assertThatThrownBy(() -> service.execute("payment:user-1", "key-aaaaaaaa", "booking-1:CARD", Result.class,
                () -> { throw new IllegalStateException("declined by the bank's API"); }))
                .isInstanceOf(IllegalStateException.class);

        var retry = pay("key-aaaaaaaa", "booking-1:CARD");
        assertThat(retry.replayed()).isFalse();
        assertThat(runs.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("keys are per user")
    void perUser() {
        pay("key-aaaaaaaa", "booking-1:CARD");
        service.execute("payment:user-2", "key-aaaaaaaa", "booking-1:CARD", Result.class, () -> {
            runs.incrementAndGet();
            return new Result("other", "SUCCESS");
        });

        assertThat(runs.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("the result is kept for 24h")
    void ttl() {
        pay("key-aaaaaaaa", "booking-1:CARD");

        Long seconds = redis.getExpire("courtly:idempotency:payment:user-1:key-aaaaaaaa", TimeUnit.SECONDS);
        assertThat(Duration.ofSeconds(seconds)).isGreaterThan(Duration.ofHours(23));
    }

    @Test
    @DisplayName("malformed keys are rejected")
    void invalidKey() {
        assertThatThrownBy(() -> pay("short", "booking-1:CARD")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> pay("has spaces in it", "booking-1:CARD")).isInstanceOf(BusinessException.class);
        assertThat(runs.get()).isZero();
    }
}
