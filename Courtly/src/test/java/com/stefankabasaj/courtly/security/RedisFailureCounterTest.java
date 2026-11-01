package com.stefankabasaj.courtly.security;

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

import static org.assertj.core.api.Assertions.assertThat;

// against a real Redis - the Lua script and the TTLs are the whole point of this class.
// skipped (not failed) on machines without Docker
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("RedisFailureCounter")
class RedisFailureCounterTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;
    private RedisFailureCounter counter;

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
        counter = new RedisFailureCounter(redis);
    }

    @Test
    @DisplayName("counts failures and sets the window on the first one")
    void countsAndExpires() {
        assertThat(counter.increment("email:a@courtly.com", Duration.ofMinutes(15))).isEqualTo(1);
        assertThat(counter.increment("email:a@courtly.com", Duration.ofMinutes(15))).isEqualTo(2);

        assertThat(counter.count("email:a@courtly.com")).isEqualTo(2);
        assertThat(counter.timeLeft("email:a@courtly.com"))
                .isGreaterThan(Duration.ofMinutes(14))
                .isLessThanOrEqualTo(Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("later failures don't push the window back")
    void windowStartsAtFirstFailure() throws InterruptedException {
        counter.increment("ip:203.0.113.7", Duration.ofSeconds(10));
        Thread.sleep(1_500);
        counter.increment("ip:203.0.113.7", Duration.ofSeconds(10));

        assertThat(counter.timeLeft("ip:203.0.113.7")).isLessThan(Duration.ofSeconds(9));
    }

    @Test
    @DisplayName("the counter disappears when the window ends")
    void expires() throws InterruptedException {
        counter.increment("email:b@courtly.com", Duration.ofMillis(300));
        Thread.sleep(600);

        assertThat(counter.count("email:b@courtly.com")).isZero();
        assertThat(counter.timeLeft("email:b@courtly.com")).isZero();
    }

    @Test
    @DisplayName("reset clears the counter")
    void reset() {
        counter.increment("email:c@courtly.com", Duration.ofMinutes(15));
        counter.reset("email:c@courtly.com");

        assertThat(counter.count("email:c@courtly.com")).isZero();
    }

    @Test
    @DisplayName("keys are namespaced")
    void prefixed() {
        counter.increment("email:d@courtly.com", Duration.ofMinutes(15));
        assertThat(redis.hasKey("courtly:login-failures:email:d@courtly.com")).isTrue();
    }
}
