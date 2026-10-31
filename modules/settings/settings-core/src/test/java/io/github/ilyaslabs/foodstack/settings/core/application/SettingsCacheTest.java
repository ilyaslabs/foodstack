package io.github.ilyaslabs.foodstack.settings.core.application;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.ilyaslabs.foodstack.platform.test.FoodstackContainers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class SettingsCacheTest {

    private LettuceConnectionFactory connectionFactory;

    private StringRedisTemplate redis;

    private SettingsCache cache;

    private String namespace;

    @BeforeEach
    void connect() {
        this.connectionFactory = new LettuceConnectionFactory(FoodstackContainers.redis().getHost(),
                FoodstackContainers.redis().getMappedPort(6379));
        this.connectionFactory.afterPropertiesSet();
        this.redis = new StringRedisTemplate(this.connectionFactory);
        this.namespace = FoodstackContainers.uniqueName();
        this.cache = new SettingsCache(this.redis, JsonMapper.builder().build(), this.namespace, Duration.ofMinutes(1));
    }

    @AfterEach
    void disconnect() {
        this.connectionFactory.destroy();
    }

    @Test
    @DisplayName("loads once, then serves from Redis under the module's key prefix until evicted")
    void readThrough() {
        // given
        AtomicInteger loads = new AtomicInteger();
        LocaleDetails locale = new LocaleDetails("en", List.of("en", "ar"), "PKR", "Asia/Karachi", 2, null);

        // when
        LocaleDetails first = this.cache.get("locale", LocaleDetails.class, () -> {
            loads.incrementAndGet();
            return locale;
        });
        LocaleDetails second = this.cache.get("locale", LocaleDetails.class, () -> {
            loads.incrementAndGet();
            return locale;
        });

        // then
        assertThat(first).isEqualTo(locale);
        assertThat(second).isEqualTo(locale);
        assertThat(loads).hasValue(1);
        assertThat(this.redis.hasKey(this.namespace + ":settings:cache:locale")).isTrue();
        assertThat(this.redis.getExpire(this.namespace + ":settings:cache:locale")).isPositive();

        // when evicted
        this.cache.evict("locale");
        this.cache.get("locale", LocaleDetails.class, () -> {
            loads.incrementAndGet();
            return locale;
        });

        // then
        assertThat(loads).hasValue(2);
    }
}
