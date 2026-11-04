package io.github.ilyaslabs.foodstack.platform.kafka;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import io.github.ilyaslabs.foodstack.platform.core.Buckets;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Splits the {@link Buckets} of one kind of background work between the replicas that run it
 * (TDD 7.5), like a consumer group: each replica heartbeats into {@code <ns>:workers:<name>},
 * takes a fair share ({@code ceil(64 / live replicas)}) of the per-bucket leases
 * {@code <ns>:workers:<name>:bucket:<n>} and gives back what is over its share when a replica
 * joins. A replica that dies stops renewing; its leases lapse and the others pick them up. One
 * replica alone holds every bucket. Every Redis command touches one key, so it runs unchanged on
 * Redis Cluster.
 */
public class BucketLeases {

    private static final Log logger = LogFactory.getLog(BucketLeases.class);

    private static final RedisScript<Long> RENEW = RedisScript.of("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              redis.call('PEXPIRE', KEYS[1], ARGV[2])
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

    private final String name;

    private final String membersKey;

    private final String bucketPrefix;

    private final Duration lease;

    private final Duration refreshEvery;

    private final Clock clock;

    private final String owner = UUID.randomUUID().toString();

    private final int start;

    private final Set<Integer> owned = new TreeSet<>();

    private long lastRefresh;

    /**
     * @param name the kind of work, e.g. {@code order:outbox} or {@code dispatch:matcher}
     * @param lease how long a lease outlives its last renewal; a dead replica's buckets move after it
     */
    public BucketLeases(StringRedisTemplate redis, String namespace, String name, Duration lease, Clock clock) {
        this.redis = redis;
        this.name = name;
        this.membersKey = namespace + ":workers:" + name;
        this.bucketPrefix = this.membersKey + ":bucket:";
        this.lease = lease;
        this.refreshEvery = lease.dividedBy(3);
        this.clock = clock;
        this.start = Buckets.of(this.owner);
    }

    /** The buckets this replica holds now, refreshing the leases when due. */
    public synchronized Set<Integer> current() {
        long now = this.clock.millis();
        if (now - this.lastRefresh >= this.refreshEvery.toMillis()) {
            try {
                refresh(now);
            }
            catch (RuntimeException ex) {
                // Redis unreachable: work on nothing rather than on buckets another replica may take over
                logger.warn("Could not refresh the " + this.name + " bucket leases", ex);
                this.owned.clear();
            }
            this.lastRefresh = now;
        }
        return Set.copyOf(this.owned);
    }

    private void refresh(long now) {
        String leaseMillis = String.valueOf(this.lease.toMillis());
        this.redis.opsForZSet().add(this.membersKey, this.owner, now + this.lease.toMillis());
        this.redis.opsForZSet().removeRangeByScore(this.membersKey, Double.NEGATIVE_INFINITY, now);
        this.redis.expire(this.membersKey, this.lease.multipliedBy(4));
        Long members = this.redis.opsForZSet().zCard(this.membersKey);
        int live = Math.max(1, (members != null) ? members.intValue() : 1);
        int share = (Buckets.COUNT + live - 1) / live;

        this.owned.removeIf((bucket) -> !isOne(this.redis.execute(RENEW, List.of(key(bucket)), this.owner, leaseMillis)));
        while (this.owned.size() > share) {
            Integer extra = ((TreeSet<Integer>) this.owned).last();
            this.redis.execute(RELEASE, List.of(key(extra)), this.owner);
            this.owned.remove(extra);
        }
        for (int i = 0; i < Buckets.COUNT && this.owned.size() < share; i++) {
            int bucket = (this.start + i) % Buckets.COUNT;
            if (!this.owned.contains(bucket) && Boolean.TRUE
                .equals(this.redis.opsForValue().setIfAbsent(key(bucket), this.owner, this.lease))) {
                this.owned.add(bucket);
            }
        }
    }

    /** Gives every lease back so the other replicas take over at once. */
    public synchronized void release() {
        try {
            for (Integer bucket : this.owned) {
                this.redis.execute(RELEASE, List.of(key(bucket)), this.owner);
            }
            this.redis.opsForZSet().remove(this.membersKey, this.owner);
        }
        catch (RuntimeException ex) {
            logger.debug("Could not release the " + this.name + " bucket leases", ex);
        }
        this.owned.clear();
        this.lastRefresh = 0;
    }

    public String name() {
        return this.name;
    }

    private String key(int bucket) {
        return this.bucketPrefix + bucket;
    }

    private static boolean isOne(Long result) {
        return result != null && result == 1L;
    }
}
