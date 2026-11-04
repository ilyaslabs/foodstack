package io.github.ilyaslabs.foodstack.dispatch.core.application;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import io.github.ilyaslabs.foodstack.platform.core.GeoCells;
import io.github.ilyaslabs.foodstack.platform.kafka.LiveChannels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Dispatch's Redis side (TDD 5.6, 8.3): one open offer per rider at a time
 * ({@code <ns>:dispatch:offer:<riderId>}, SET NX with expiry), and live channels
 * {@code <ns>:dispatch:rt:rider:<riderId>} (the rider app) and {@code <ns>:dispatch:rt:cell:<cell>}
 * (the operator's live board, which follows the {@link GeoCells map cells} it shows, by the job's
 * pickup point; TDD 7.5). Messages carry ids and statuses only; screens reload what changed.
 */
public class DispatchLive {

    private static final Logger log = LoggerFactory.getLogger(DispatchLive.class);

    private static final RedisScript<Long> RELEASE = RedisScript.of("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;

    private final LiveChannels live;

    private final String prefix;

    public DispatchLive(StringRedisTemplate redis, LiveChannels live, String namespace) {
        this.redis = redis;
        this.live = live;
        this.prefix = namespace + ":dispatch:";
    }

    /** Holds the rider for one offer; {@code false} when the rider has another offer open. */
    public boolean holdRider(String riderId, String orderId, Duration ttl) {
        try {
            return Boolean.TRUE.equals(this.redis.opsForValue().setIfAbsent(this.prefix + "offer:" + riderId, orderId, ttl));
        }
        catch (DataAccessException ex) {
            log.warn("Offer lock for rider {} not taken: {}", riderId, ex.getMessage());
            return false;
        }
    }

    /** Frees the rider if the hold is for this order. */
    public void releaseRider(String riderId, String orderId) {
        if (riderId == null) {
            return;
        }
        try {
            this.redis.execute(RELEASE, List.of(this.prefix + "offer:" + riderId), orderId);
        }
        catch (DataAccessException ex) {
            // expires on its own
        }
    }

    public boolean riderHeld(String riderId) {
        try {
            return Boolean.TRUE.equals(this.redis.hasKey(this.prefix + "offer:" + riderId));
        }
        catch (DataAccessException ex) {
            return false;
        }
    }

    /** Tells a rider's app and the live boards showing the pickup point that a job changed. */
    public void publish(String orderId, double pickupLat, double pickupLng, String status, String riderId, String kind) {
        String message = "{\"orderId\":\"" + orderId + "\",\"status\":\"" + status + "\",\"kind\":\"" + kind + "\""
                + ((riderId != null) ? ",\"riderId\":\"" + riderId + "\"" : "") + "}";
        if (riderId != null) {
            this.live.publish(this.prefix + "rt:rider:" + riderId, message);
        }
        this.live.publish(this.prefix + "rt:cell:" + GeoCells.of(pickupLat, pickupLng), message);
    }

    public AutoCloseable onRider(String riderId, Consumer<String> receiver) {
        return this.live.subscribe(this.prefix + "rt:rider:" + riderId, receiver);
    }

    /** Receives job changes with a pickup in these cells (a live board's view) until closed. */
    public AutoCloseable onCells(List<String> cells, Consumer<String> receiver) {
        List<AutoCloseable> subscriptions = new ArrayList<>(cells.size());
        for (String cell : cells) {
            subscriptions.add(this.live.subscribe(this.prefix + "rt:cell:" + cell, receiver));
        }
        return () -> {
            for (AutoCloseable subscription : subscriptions) {
                subscription.close();
            }
        };
    }
}
