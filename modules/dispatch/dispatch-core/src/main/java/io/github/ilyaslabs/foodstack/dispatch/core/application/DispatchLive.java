package io.github.ilyaslabs.foodstack.dispatch.core.application;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Dispatch's Redis side (TDD 5.6, 8.3): one open offer per rider at a time
 * ({@code <ns>:dispatch:offer:<riderId>}, SET NX with expiry), and live channels
 * {@code <ns>:dispatch:rt:rider:<riderId>} (the rider app) and {@code <ns>:dispatch:rt:all} (the
 * operator's live map). Messages carry ids and statuses only; screens reload what changed.
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

    private final RedisMessageListenerContainer listeners;

    private final String prefix;

    public DispatchLive(StringRedisTemplate redis, RedisMessageListenerContainer listeners, String namespace) {
        this.redis = redis;
        this.listeners = listeners;
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
            this.redis.execute(RELEASE, java.util.List.of(this.prefix + "offer:" + riderId), orderId);
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

    /** Tells a rider's app and the live map that a job changed. */
    public void publish(String orderId, String status, String riderId, String kind) {
        String message = "{\"orderId\":\"" + orderId + "\",\"status\":\"" + status + "\",\"kind\":\"" + kind + "\""
                + ((riderId != null) ? ",\"riderId\":\"" + riderId + "\"" : "") + "}";
        if (riderId != null) {
            send(this.prefix + "rt:rider:" + riderId, message);
        }
        send(this.prefix + "rt:all", message);
    }

    public AutoCloseable onRider(String riderId, Consumer<String> receiver) {
        return subscribe(this.prefix + "rt:rider:" + riderId, receiver);
    }

    public AutoCloseable onAll(Consumer<String> receiver) {
        return subscribe(this.prefix + "rt:all", receiver);
    }

    private void send(String channel, String message) {
        try {
            this.redis.convertAndSend(channel, message);
        }
        catch (DataAccessException ex) {
            log.debug("Live message on {} not sent: {}", channel, ex.getMessage());
        }
    }

    private AutoCloseable subscribe(String channel, Consumer<String> receiver) {
        ChannelTopic topic = new ChannelTopic(channel);
        MessageListener listener = (message, pattern) -> receiver.accept(new String(message.getBody(), StandardCharsets.UTF_8));
        this.listeners.addMessageListener(listener, topic);
        return () -> this.listeners.removeMessageListener(listener, topic);
    }
}
