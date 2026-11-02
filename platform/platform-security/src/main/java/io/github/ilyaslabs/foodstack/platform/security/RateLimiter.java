package io.github.ilyaslabs.foodstack.platform.security;

import java.time.Clock;
import java.time.Duration;

import io.github.ilyaslabs.foodstack.platform.core.TooManyRequestsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Per-user, per-action limits in the app (TDD 8.1), on top of the gateway's per-IP limits. A fixed
 * window counter in Redis under {@code <ns>:<module>:rl:<scope>:<id>:<window>}; one key per
 * window, so it works on Redis Cluster. When Redis is down the limit is skipped (fail open), as at
 * the edge.
 */
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final StringRedisTemplate redis;

    private final String namespace;

    private final Clock clock;

    public RateLimiter(StringRedisTemplate redis, String namespace, Clock clock) {
        this.redis = redis;
        this.namespace = namespace;
        this.clock = clock;
    }

    /**
     * Counts one use and throws {@link TooManyRequestsException} once {@code limit} uses fall in the same
     * {@code window}.
     */
    public void check(String module, String scope, String id, int limit, Duration window) {
        long windowMillis = window.toMillis();
        long now = this.clock.millis();
        long index = now / windowMillis;
        String key = this.namespace + ":" + module + ":rl:" + scope + ":" + id + ":" + index;
        Long count;
        try {
            count = this.redis.opsForValue().increment(key);
            if (count != null && count == 1) {
                this.redis.expire(key, window.plusSeconds(1));
            }
        }
        catch (DataAccessException ex) {
            log.warn("Rate limit {} skipped: Redis is not answering", scope);
            return;
        }
        if (count != null && count > limit) {
            long retryAfter = Math.max(1, ((index + 1) * windowMillis - now) / 1000);
            throw new TooManyRequestsException("Too many attempts; try again in a few minutes", retryAfter);
        }
    }
}
