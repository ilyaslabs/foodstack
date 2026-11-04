package io.github.ilyaslabs.foodstack.platform.kafka;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.models.partitions.RedisClusterNode;
import io.lettuce.core.cluster.pubsub.RedisClusterPubSubAdapter;
import io.lettuce.core.cluster.pubsub.StatefulRedisClusterPubSubConnection;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.DisposableBean;

/**
 * {@link LiveChannels} on Redis Cluster: sharded pub/sub. A channel is hashed to a slot like a
 * key, so its messages travel only to the node owning that slot instead of to every node, and
 * each replica holds one subscription per channel however many browsers follow it.
 */
public class ShardedLiveChannels implements LiveChannels, DisposableBean {

    private static final Log logger = LogFactory.getLog(ShardedLiveChannels.class);

    private final StatefulRedisClusterConnection<String, String> publisher;

    private final StatefulRedisClusterPubSubConnection<String, String> subscriber;

    private final Map<String, Set<Consumer<String>>> receivers = new ConcurrentHashMap<>();

    public ShardedLiveChannels(RedisClusterClient client) {
        this.publisher = client.connect();
        this.subscriber = client.connectPubSub();
        this.subscriber.addListener(new RedisClusterPubSubAdapter<>() {

            @Override
            public void smessage(RedisClusterNode node, String channel, String message) {
                deliver(channel, message);
            }
        });
    }

    @Override
    public void publish(String channel, String message) {
        try {
            this.publisher.async().spublish(channel, message);
        }
        catch (RuntimeException ex) {
            logger.debug("Live message on " + channel + " not sent: " + ex.getMessage());
        }
    }

    @Override
    public AutoCloseable subscribe(String channel, Consumer<String> receiver) {
        Set<Consumer<String>> set = this.receivers.compute(channel, (key, existing) -> {
            Set<Consumer<String>> current = (existing != null) ? existing : new CopyOnWriteArraySet<>();
            if (existing == null) {
                this.subscriber.sync().ssubscribe(channel);
            }
            return current;
        });
        set.add(receiver);
        return () -> this.receivers.computeIfPresent(channel, (key, current) -> {
            current.remove(receiver);
            if (current.isEmpty()) {
                try {
                    this.subscriber.sync().sunsubscribe(channel);
                }
                catch (RuntimeException ex) {
                    logger.debug("Could not leave live channel " + channel, ex);
                }
                return null;
            }
            return current;
        });
    }

    private void deliver(String channel, String message) {
        Set<Consumer<String>> set = this.receivers.get(channel);
        if (set == null) {
            return;
        }
        for (Consumer<String> receiver : set) {
            try {
                receiver.accept(message);
            }
            catch (RuntimeException ex) {
                logger.debug("A live receiver on " + channel + " failed", ex);
            }
        }
    }

    @Override
    public void destroy() {
        this.subscriber.close();
        this.publisher.close();
    }
}
