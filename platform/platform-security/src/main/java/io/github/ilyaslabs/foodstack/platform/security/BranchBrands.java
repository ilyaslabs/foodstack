package io.github.ilyaslabs.foodstack.platform.security;

import java.util.Optional;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Which brand each branch belongs to, so a brand owner's token (a {@code brandIds} claim only)
 * opens every branch of the brand in every module without listing the branches in the token
 * (PRD BB-5, TDD 9.1). The store module writes {@code <ns>:store:branch-brand:<storeId>} when a
 * branch is created and again at start-up; a branch never changes brand, so each process keeps
 * the answers in a bounded in-memory cache with no expiry. An unknown branch, or Redis being
 * unreachable, means no brand: access is refused, never guessed.
 */
public class BranchBrands {

    /** Default for how many branches each process keeps in memory. */
    public static final long DEFAULT_MAX_CACHED = 200_000;

    private final StringRedisTemplate redis;

    private final String prefix;

    private final Cache<String, String> cache;

    public BranchBrands(StringRedisTemplate redis, String namespace, long maxCached) {
        this.redis = redis;
        this.prefix = namespace + ":store:branch-brand:";
        this.cache = Caffeine.newBuilder().maximumSize(Math.max(1, maxCached)).build();
    }

    /** The brand of a branch, if known. */
    public Optional<String> brandOf(String storeId) {
        if (storeId == null || storeId.isBlank()) {
            return Optional.empty();
        }
        String cached = this.cache.getIfPresent(storeId);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            String brandId = this.redis.opsForValue().get(this.prefix + storeId);
            if (brandId != null) {
                this.cache.put(storeId, brandId);
            }
            return Optional.ofNullable(brandId);
        }
        catch (DataAccessException ex) {
            return Optional.empty();
        }
    }

    /** Records a branch's brand; the store module calls this when a branch is created and at start-up. */
    public void remember(String storeId, String brandId) {
        this.redis.opsForValue().set(this.prefix + storeId, brandId);
        this.cache.put(storeId, brandId);
    }
}
