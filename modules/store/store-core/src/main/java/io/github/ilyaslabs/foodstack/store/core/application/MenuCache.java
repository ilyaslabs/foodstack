package io.github.ilyaslabs.foodstack.store.core.application;

import java.time.Duration;
import java.util.Collection;
import java.util.function.Supplier;

import tools.jackson.databind.json.JsonMapper;

import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Each branch's effective menu in Redis under {@code <ns>:store:menu:<storeId>:<generation>} (TDD
 * 5.7), shared by every replica. A change bumps the branch's generation instead of deleting the
 * entry, so a menu read that started before the change and finishes after it writes to the old
 * generation, which nobody reads any more, and can never hide the change.
 */
public class MenuCache {

    private final StringRedisTemplate redis;

    private final JsonMapper jsonMapper;

    private final String prefix;

    private final Duration ttl;

    public MenuCache(StringRedisTemplate redis, JsonMapper jsonMapper, String namespace, Duration ttl) {
        this.redis = redis;
        this.jsonMapper = jsonMapper;
        this.prefix = namespace + ":store:menu:";
        this.ttl = ttl;
    }

    public EffectiveMenu get(String storeId, Supplier<EffectiveMenu> loader) {
        String generation = this.redis.opsForValue().get(generationKey(storeId));
        String key = this.prefix + storeId + ":" + ((generation != null) ? generation : "0");
        String cached = this.redis.opsForValue().get(key);
        if (cached != null) {
            return this.jsonMapper.readValue(cached, EffectiveMenu.class);
        }
        EffectiveMenu menu = loader.get();
        this.redis.opsForValue().set(key, this.jsonMapper.writeValueAsString(menu), this.ttl);
        return menu;
    }

    public void evict(Collection<String> storeIds) {
        for (String storeId : storeIds) {
            String key = generationKey(storeId);
            this.redis.opsForValue().increment(key);
            // outlives every entry of the old generation
            this.redis.expire(key, this.ttl.multipliedBy(2));
        }
    }

    private String generationKey(String storeId) {
        return this.prefix + "gen:" + storeId;
    }
}
