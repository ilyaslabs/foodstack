package io.github.ilyaslabs.foodstack.order.core.application;

import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Live order changes across replicas (TDD 8.3): after each change the order module publishes a
 * small message (order id, status, revision; no personal data) on Redis channels
 * {@code <ns>:order:rt:order:<orderId>} and {@code <ns>:order:rt:store:<storeId>}; the replica
 * holding a customer's or a store's stream forwards it, and the screen reloads what it shows.
 */
public class OrderLiveUpdates {

    private static final Logger log = LoggerFactory.getLogger(OrderLiveUpdates.class);

    private final StringRedisTemplate redis;

    private final RedisMessageListenerContainer listeners;

    private final String prefix;

    public OrderLiveUpdates(StringRedisTemplate redis, RedisMessageListenerContainer listeners, String namespace) {
        this.redis = redis;
        this.listeners = listeners;
        this.prefix = namespace + ":order:rt:";
    }

    /** Tells streams about a change. A lost message only delays the screen until its next refresh. */
    public void publish(String orderId, String storeId, String status, long revision) {
        String message = "{\"orderId\":\"" + orderId + "\",\"status\":\"" + status + "\",\"revision\":" + revision + "}";
        try {
            this.redis.convertAndSend(this.prefix + "order:" + orderId, message);
            this.redis.convertAndSend(this.prefix + "store:" + storeId, message);
        }
        catch (DataAccessException ex) {
            log.warn("Live update for order {} not sent: {}", orderId, ex.getMessage());
        }
    }

    /** Receives changes of one order until the returned handle is closed. */
    public AutoCloseable onOrder(String orderId, Consumer<String> receiver) {
        return subscribe(this.prefix + "order:" + orderId, receiver);
    }

    /** Receives changes of a branch's orders until the returned handle is closed. */
    public AutoCloseable onStore(String storeId, Consumer<String> receiver) {
        return subscribe(this.prefix + "store:" + storeId, receiver);
    }

    private AutoCloseable subscribe(String channel, Consumer<String> receiver) {
        ChannelTopic topic = new ChannelTopic(channel);
        MessageListener listener = (message, pattern) -> receiver
            .accept(new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8));
        this.listeners.addMessageListener(listener, topic);
        return () -> this.listeners.removeMessageListener(listener, topic);
    }
}
