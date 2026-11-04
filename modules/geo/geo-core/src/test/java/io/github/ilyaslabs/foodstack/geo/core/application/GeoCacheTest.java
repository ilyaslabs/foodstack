package io.github.ilyaslabs.foodstack.geo.core.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import io.github.ilyaslabs.foodstack.platform.test.FoodstackContainers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class GeoCacheTest {

    private LettuceConnectionFactory connectionFactory;

    private GeoCache cache;

    @BeforeEach
    void connect() {
        this.connectionFactory = new LettuceConnectionFactory(FoodstackContainers.redis().getHost(),
                FoodstackContainers.redis().getMappedPort(6379));
        this.connectionFactory.afterPropertiesSet();
        this.cache = new GeoCache(new StringRedisTemplate(this.connectionFactory), JsonMapper.builder().build(),
                FoodstackContainers.uniqueName(), Duration.ofMinutes(10));
    }

    @AfterEach
    void disconnect() {
        this.connectionFactory.destroy();
    }

    @Test
    @DisplayName("serves a branch's zones from Redis until they change")
    void zonesUntilEvicted() {
        assertThat(this.cache.zones("s1", () -> zones("a"))).isEqualTo(zones("a"));
        assertThat(this.cache.zones("s1", () -> zones("b"))).isEqualTo(zones("a"));
        assertThat(this.cache.zones("s2", () -> zones("c"))).isEqualTo(zones("c"));
        this.cache.evictZones("s1");
        assertThat(this.cache.zones("s1", () -> zones("b"))).isEqualTo(zones("b"));
        assertThat(this.cache.zones("s2", () -> zones("d"))).isEqualTo(zones("c"));
    }

    @Test
    @DisplayName("serves the operator's pricing from Redis until it changes")
    void pricingUntilEvicted() {
        assertThat(this.cache.operatorPricing(() -> pricing(1)).version()).isEqualTo(1);
        assertThat(this.cache.operatorPricing(() -> pricing(2)).version()).isEqualTo(1);
        this.cache.evictOperatorPricing();
        OperatorPricing reloaded = this.cache.operatorPricing(() -> pricing(2));
        assertThat(reloaded).isEqualTo(pricing(2));
    }

    @Test
    @DisplayName("a read that started before a change cannot cache the old zones after it")
    void lateReaderDoesNotHideAChange() {
        List<ZoneDetails> stale = this.cache.zones("s3", () -> {
            this.cache.evictZones("s3");
            return zones("old");
        });
        assertThat(stale).isEqualTo(zones("old"));
        assertThat(this.cache.zones("s3", () -> zones("new"))).isEqualTo(zones("new"));
    }

    @Test
    @DisplayName("without Redis every read goes to the database")
    void none() {
        GeoCache none = GeoCache.none();
        assertThat(none.zones("s1", () -> zones("a"))).isEqualTo(zones("a"));
        assertThat(none.zones("s1", () -> zones("b"))).isEqualTo(zones("b"));
        none.evictZones("s1");
    }

    private static List<ZoneDetails> zones(String name) {
        List<GeoPoint> square = List.of(new GeoPoint(31.5, 74.3), new GeoPoint(31.5, 74.4), new GeoPoint(31.6, 74.4));
        return List.of(new ZoneDetails("z-" + name, name, new GeoPoint(31.55, 74.35), 3000, square, 15_000, 50_000, null,
                Instant.parse("2026-11-04T10:00:00Z")));
    }

    private static OperatorPricing pricing(long version) {
        return new OperatorPricing(true, 10_000, 3_000, 8000, 0, 200_000L, 150,
                List.of(new PeakWindow(List.of(java.time.DayOfWeek.FRIDAY), "19:00", "22:00")), version, Instant.parse("2026-11-04T10:00:00Z"));
    }
}
