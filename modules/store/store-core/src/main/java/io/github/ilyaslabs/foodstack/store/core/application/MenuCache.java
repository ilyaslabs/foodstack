package io.github.ilyaslabs.foodstack.store.core.application;

import java.time.Duration;
import java.util.Collection;
import java.util.function.Supplier;

import tools.jackson.databind.json.JsonMapper;

import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Each branch's effective menu in Redis under {@code <ns>:store:menu:<storeId>} (TDD 5.7), shared
 * by every replica and evicted whenever the brand menu or the branch's sold-out marks change.
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
        String cached = this.redis.opsForValue().get(this.prefix + storeId);
        if (cached != null) {
            return this.jsonMapper.readValue(cached, EffectiveMenu.class);
        }
        EffectiveMenu menu = loader.get();
        this.redis.opsForValue().set(this.prefix + storeId, this.jsonMapper.writeValueAsString(menu), this.ttl);
        return menu;
    }

    public void evict(Collection<String> storeIds) {
        if (!storeIds.isEmpty()) {
            this.redis.delete(storeIds.stream().map((id) -> this.prefix + id).toList());
        }
    }
}
