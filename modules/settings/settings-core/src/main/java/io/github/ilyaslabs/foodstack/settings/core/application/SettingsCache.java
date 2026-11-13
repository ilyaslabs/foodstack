package io.github.ilyaslabs.foodstack.settings.core.application;

import java.time.Duration;
import java.util.function.Supplier;

import io.github.ilyaslabs.evl.EVL;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Read-through cache of settings sections in Redis, under {@code <ns>:settings:cache:<section>}
 * (TDD 10). Shared by every replica; evicted on change.
 */
@EVL.Cache(id = "settings.cache", name = "Settings Cache", store = "Redis",
        key = "<namespace>:settings:cache:<section>", ttl = "foodstack.settings.cache-ttl (default 10m)",
        invalidation = "Evicted by the settings services right after a change is saved")
@EVL.Doc(value = "Shared by every replica of the settings module.", decisions = "adr.settings-caching")
public class SettingsCache {

    private final StringRedisTemplate redis;

    private final JsonMapper jsonMapper;

    private final String prefix;

    private final Duration ttl;

    public SettingsCache(StringRedisTemplate redis, JsonMapper jsonMapper, String namespace, Duration ttl) {
        this.redis = redis;
        this.jsonMapper = jsonMapper;
        this.prefix = namespace + ":settings:cache:";
        this.ttl = ttl;
    }

    public <T> T get(String section, Class<T> type, Supplier<T> loader) {
        String cached = this.redis.opsForValue().get(this.prefix + section);
        if (cached != null) {
            return this.jsonMapper.readValue(cached, type);
        }
        T value = loader.get();
        this.redis.opsForValue().set(this.prefix + section, this.jsonMapper.writeValueAsString(value), this.ttl);
        return value;
    }

    public void evict(String section) {
        this.redis.delete(this.prefix + section);
    }
}
