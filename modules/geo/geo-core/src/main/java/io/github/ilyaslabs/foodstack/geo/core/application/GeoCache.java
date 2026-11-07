package io.github.ilyaslabs.foodstack.geo.core.application;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * What every delivery quote reads, kept in Redis so quotes stop hitting the geo database
 * (TDD 7.5, gap 7): a branch's zones under {@code <ns>:geo:cache:zones:<storeId>:<generation>}
 * and the operator's pricing under {@code <ns>:geo:cache:operator-pricing:<generation>}. Entries
 * live 10 minutes by default. A change bumps the generation right after it commits, and again
 * when its event comes back from Kafka (in case the process died in between), the same way as
 * the menu cache: a read that started before the change writes to the old generation, which
 * nobody reads, so it can never hide the change. Shared by every replica, so a change is seen
 * everywhere at once. When Redis is down, reads go to the database.
 */
public class GeoCache {

    private static final TypeReference<List<ZoneDetails>> ZONES = new TypeReference<>() {
    };

    private final StringRedisTemplate redis;

    private final JsonMapper jsonMapper;

    private final String prefix;

    private final Duration ttl;

    public GeoCache(StringRedisTemplate redis, JsonMapper jsonMapper, String namespace, Duration ttl) {
        this.redis = redis;
        this.jsonMapper = jsonMapper;
        this.prefix = namespace + ":geo:cache:";
        this.ttl = ttl;
    }

    /** No cache: every read goes to the database. */
    public static GeoCache none() {
        return new GeoCache(null, null, "", Duration.ZERO);
    }

    public List<ZoneDetails> zones(String storeId, Supplier<List<ZoneDetails>> loader) {
        return read(zonesKey(storeId), ZONES, loader);
    }

    public OperatorPricing operatorPricing(Supplier<OperatorPricing> loader) {
        return read(pricingKey(), new TypeReference<>() {
        }, loader);
    }

    public List<City> cities(Supplier<List<City>> loader) {
        return read(this.prefix + "cities", new TypeReference<>() {
        }, loader);
    }

    public void evictCities() {
        evict(this.prefix + "cities");
    }

    public void evictZones(String storeId) {
        evict(zonesKey(storeId));
    }

    public void evictOperatorPricing() {
        evict(pricingKey());
    }

    String zonesKey(String storeId) {
        return this.prefix + "zones:" + storeId;
    }

    String pricingKey() {
        return this.prefix + "operator-pricing";
    }

    private <T> T read(String name, TypeReference<T> type, Supplier<T> loader) {
        if (this.redis == null || this.ttl.isZero()) {
            return loader.get();
        }
        String key;
        try {
            String generation = this.redis.opsForValue().get(name + ":gen");
            key = name + ":" + ((generation != null) ? generation : "0");
            String cached = this.redis.opsForValue().get(key);
            if (cached != null) {
                return this.jsonMapper.readValue(cached, type);
            }
        }
        catch (DataAccessException | JacksonException ex) {
            // read from the database instead
            return loader.get();
        }
        T value = loader.get();
        try {
            this.redis.opsForValue().set(key, this.jsonMapper.writeValueAsString(value), this.ttl);
        }
        catch (DataAccessException | JacksonException ex) {
            // the next read loads it again
        }
        return value;
    }

    private void evict(String name) {
        if (this.redis == null) {
            return;
        }
        try {
            String generation = name + ":gen";
            this.redis.opsForValue().increment(generation);
            // outlives every entry of the old generation
            this.redis.expire(generation, this.ttl.multipliedBy(2));
        }
        catch (DataAccessException ex) {
            // the entry expires on its own; the change event evicts it again
        }
    }
}
