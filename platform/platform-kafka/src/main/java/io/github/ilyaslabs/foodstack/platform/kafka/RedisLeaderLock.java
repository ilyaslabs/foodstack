package io.github.ilyaslabs.foodstack.platform.kafka;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * A lease held by one instance at a time, renewed while it keeps working. Used so that only one
 * replica relays a module's outbox (TDD 7.2, key {@code <ns>:<module>:outbox:leader}).
 */
public class RedisLeaderLock {

    private static final RedisScript<Long> ACQUIRE_OR_RENEW = RedisScript.of("""
            local current = redis.call('GET', KEYS[1])
            if current == ARGV[1] then
              redis.call('PEXPIRE', KEYS[1], ARGV[2])
              return 1
            end
            if current == false then
              redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
              return 1
            end
            return 0
            """, Long.class);

    private static final RedisScript<Long> RELEASE = RedisScript.of("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;

    private final String key;

    private final Duration lease;

    private final String owner = UUID.randomUUID().toString();

    public RedisLeaderLock(StringRedisTemplate redis, String key, Duration lease) {
        this.redis = redis;
        this.key = key;
        this.lease = lease;
    }

    /** Takes the lease if free, or extends it if this instance holds it. */
    public boolean acquireOrRenew() {
        Long result = this.redis.execute(ACQUIRE_OR_RENEW, List.of(this.key), this.owner,
                String.valueOf(this.lease.toMillis()));
        return result != null && result == 1L;
    }

    public void release() {
        this.redis.execute(RELEASE, List.of(this.key), this.owner);
    }

    public String key() {
        return this.key;
    }
}
