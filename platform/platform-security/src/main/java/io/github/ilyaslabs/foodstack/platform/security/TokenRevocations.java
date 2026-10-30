package io.github.ilyaslabs.foodstack.platform.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Immediate token revocation (TDD 9.1): the lowest token version still valid per user, in Redis
 * under {@code <ns>:identity:revoked:<userId>}, with a short in-memory cache in each module.
 * <p>
 * {@code identity} raises the version when an account is blocked, a password changes, roles are
 * removed or the user logs out everywhere; every module then rejects older tokens.
 */
public class TokenRevocations {

    private final StringRedisTemplate redis;

    private final String namespace;

    private final Duration cacheTtl;

    private final Clock clock;

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public TokenRevocations(StringRedisTemplate redis, String namespace, Duration cacheTtl, Clock clock) {
        this.redis = redis;
        this.namespace = namespace;
        this.cacheTtl = cacheTtl;
        this.clock = clock;
    }

    /** Whether a token with {@code tokenVersion} of {@code userId} has been revoked. */
    public boolean isRevoked(String userId, long tokenVersion) {
        return tokenVersion < minimumVersion(userId);
    }

    /** Revokes every token of the user older than {@code minimumVersion}. */
    public void revokeBefore(String userId, long minimumVersion) {
        this.redis.opsForValue().set(key(userId), String.valueOf(minimumVersion));
        this.cache.remove(userId);
    }

    long minimumVersion(String userId) {
        Instant now = this.clock.instant();
        Cached cached = this.cache.get(userId);
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return cached.minimumVersion();
        }
        String value = this.redis.opsForValue().get(key(userId));
        long minimum = (value != null) ? Long.parseLong(value) : 0L;
        this.cache.put(userId, new Cached(minimum, now.plus(this.cacheTtl)));
        return minimum;
    }

    private String key(String userId) {
        return this.namespace + ":identity:revoked:" + userId;
    }

    private record Cached(long minimumVersion, Instant expiresAt) {
    }
}
