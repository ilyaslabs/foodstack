package io.github.ilyaslabs.foodstack.store.core.application;

import java.time.Duration;
import java.util.List;

import io.github.ilyaslabs.foodstack.platform.test.FoodstackContainers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class MenuCacheTest {

    private LettuceConnectionFactory connectionFactory;

    private MenuCache cache;

    @BeforeEach
    void connect() {
        this.connectionFactory = new LettuceConnectionFactory(FoodstackContainers.redis().getHost(),
                FoodstackContainers.redis().getMappedPort(6379));
        this.connectionFactory.afterPropertiesSet();
        this.cache = new MenuCache(new StringRedisTemplate(this.connectionFactory), JsonMapper.builder().build(),
                FoodstackContainers.uniqueName(), Duration.ofMinutes(5));
    }

    @AfterEach
    void disconnect() {
        this.connectionFactory.destroy();
    }

    @Test
    @DisplayName("serves the cached menu until a change, then loads it again")
    void cachesUntilEvicted() {
        assertThat(this.cache.get("s1", () -> menu("s1", "v1")).currency()).isEqualTo("v1");
        assertThat(this.cache.get("s1", () -> menu("s1", "v2")).currency()).isEqualTo("v1");
        this.cache.evict(List.of("s1"));
        assertThat(this.cache.get("s1", () -> menu("s1", "v2")).currency()).isEqualTo("v2");
    }

    @Test
    @DisplayName("a read that started before a change cannot cache the old menu after it")
    void lateReaderDoesNotHideAChange() {
        // the read loads the old menu, and the change lands (and evicts) before the read stores it
        EffectiveMenu stale = this.cache.get("s2", () -> {
            this.cache.evict(List.of("s2"));
            return menu("s2", "old");
        });
        assertThat(stale.currency()).isEqualTo("old");
        assertThat(this.cache.get("s2", () -> menu("s2", "new")).currency()).isEqualTo("new");
    }

    private static EffectiveMenu menu(String storeId, String marker) {
        return new EffectiveMenu(storeId, "b1", marker, List.of());
    }
}
