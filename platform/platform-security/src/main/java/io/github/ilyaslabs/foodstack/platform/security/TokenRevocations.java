package io.github.ilyaslabs.foodstack.platform.security;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Immediate token revocation (TDD 9.1): the lowest token version still valid per user, in Redis
 * under {@code <ns>:identity:revoked:<userId>}, with a short, size-bounded in-memory cache
 * (Caffeine) in each module, so memory stays flat however many users are active.
 * <p>
 * {@code identity} raises the version when an account is blocked, a password changes, roles are
 * removed or the user logs out everywhere; every module then rejects older tokens.
 */
public class TokenRevocations {

    /** Default for how many users' versions each process keeps in memory. */
    public static final long DEFAULT_MAX_CACHED = 100_000;

    private final StringRedisTemplate redis;

    private final String namespace;

    private final Cache<String, Long> cache;

    public TokenRevocations(StringRedisTemplate redis, String namespace, Duration cacheTtl, Clock clock) {
        this(redis, namespace, cacheTtl, DEFAULT_MAX_CACHED, clock);
    }

    /**
     * @param cacheTtl how long a version read from Redis is trusted in memory; zero for none
     * @param maxCached most users kept in memory, least recently used dropped first
     */
    public TokenRevocations(StringRedisTemplate redis, String namespace, Duration cacheTtl, long maxCached, Clock clock) {
        this.redis = redis;
        this.namespace = namespace;
        this.cache = (cacheTtl.isZero() || maxCached <= 0) ? null
                : Caffeine.newBuilder()
                    .maximumSize(maxCached)
                    .expireAfterWrite(cacheTtl)
                    .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis()))
                    .build();
    }

    /** Whether a token with {@code tokenVersion} of {@code userId} has been revoked. */
    public boolean isRevoked(String userId, long tokenVersion) {
        return tokenVersion < minimumVersion(userId);
    }

    /** Revokes every token of the user older than {@code minimumVersion}. */
    public void revokeBefore(String userId, long minimumVersion) {
        this.redis.opsForValue().set(key(userId), String.valueOf(minimumVersion));
        if (this.cache != null) {
            this.cache.invalidate(userId);
        }
    }

    long minimumVersion(String userId) {
        return (this.cache != null) ? this.cache.get(userId, this::read) : read(userId);
    }

    /** How many users' versions are held in memory now. */
    long cachedUsers() {
        if (this.cache == null) {
            return 0;
        }
        this.cache.cleanUp();
        return this.cache.estimatedSize();
    }

    private long read(String userId) {
        String value = this.redis.opsForValue().get(key(userId));
        return (value != null) ? Long.parseLong(value) : 0L;
    }

    private String key(String userId) {
        return this.namespace + ":identity:revoked:" + userId;
    }
}
