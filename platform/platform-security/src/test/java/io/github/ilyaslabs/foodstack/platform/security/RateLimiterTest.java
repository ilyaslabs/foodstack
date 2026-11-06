package io.github.ilyaslabs.foodstack.platform.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import io.github.ilyaslabs.foodstack.platform.core.TooManyRequestsException;
import io.github.ilyaslabs.foodstack.platform.test.FoodstackContainers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimiterTest {

    private LettuceConnectionFactory connectionFactory;

    private StringRedisTemplate redis;

    @BeforeEach
    void connect() {
        this.connectionFactory = new LettuceConnectionFactory(FoodstackContainers.redis().getHost(),
                FoodstackContainers.redis().getMappedPort(6379));
        this.connectionFactory.afterPropertiesSet();
        this.redis = new StringRedisTemplate(this.connectionFactory);
    }

    @AfterEach
    void disconnect() {
        this.connectionFactory.destroy();
    }

    @Test
    @DisplayName("allows the limit per user and window, then answers 429 until the next window")
    void limits() {
        String namespace = FoodstackContainers.uniqueName();
        Instant start = Instant.parse("2026-11-01T12:00:00Z");
        RateLimiter limiter = new RateLimiter(this.redis, namespace, Clock.fixed(start, ZoneOffset.UTC));
        for (int i = 0; i < 3; i++) {
            limiter.check("order", "place", "c1", 3, Duration.ofMinutes(10));
        }
        assertThatThrownBy(() -> limiter.check("order", "place", "c1", 3, Duration.ofMinutes(10)))
            .isInstanceOfSatisfying(TooManyRequestsException.class,
                    (ex) -> org.assertj.core.api.Assertions.assertThat(ex.retryAfterSeconds()).isEqualTo(600));
        limiter.check("order", "place", "c2", 3, Duration.ofMinutes(10));
        new RateLimiter(this.redis, namespace, Clock.fixed(start.plus(Duration.ofMinutes(10)), ZoneOffset.UTC))
            .check("order", "place", "c1", 3, Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("refuses once the limit is counted, without counting a use itself")
    void refusesWithoutCounting() {
        String namespace = FoodstackContainers.uniqueName();
        RateLimiter limiter = new RateLimiter(this.redis, namespace,
                Clock.fixed(Instant.parse("2026-11-01T12:00:00Z"), ZoneOffset.UTC));
        for (int i = 0; i < 5; i++) {
            limiter.refuseIfReached("identity", "password", "e1", 2, Duration.ofMinutes(15));
        }
        limiter.check("identity", "password", "e1", 2, Duration.ofMinutes(15));
        limiter.refuseIfReached("identity", "password", "e1", 2, Duration.ofMinutes(15));
        limiter.check("identity", "password", "e1", 2, Duration.ofMinutes(15));
        assertThatThrownBy(() -> limiter.refuseIfReached("identity", "password", "e1", 2, Duration.ofMinutes(15)))
            .isInstanceOf(TooManyRequestsException.class);
    }
}
