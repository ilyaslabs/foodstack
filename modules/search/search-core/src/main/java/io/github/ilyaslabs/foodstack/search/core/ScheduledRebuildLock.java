package io.github.ilyaslabs.foodstack.search.core;

import io.github.ilyaslabs.evl.EVL;
import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Lets one instance run each scheduled rebuild: the first to set the key {@code <ns>:search:rebuild}
 * runs it, and the key lives a little less than the interval so the next round is free again.
 */
@EVL.Cache("Redis")
class ScheduledRebuildLock {

    private final StringRedisTemplate redis;

    private final String key;

    ScheduledRebuildLock(StringRedisTemplate redis, String key) {
        this.redis = redis;
        this.key = key;
    }

    boolean tryTake(Duration interval) {
        Duration hold = interval.minus(interval.dividedBy(10));
        return Boolean.TRUE.equals(this.redis.opsForValue().setIfAbsent(this.key, "taken", hold));
    }
}
