package io.github.ilyaslabs.foodstack.platform.kafka;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/** {@link LiveChannels} on standalone or Sentinel Redis: {@code PUBLISH}/{@code SUBSCRIBE}. */
public class PlainLiveChannels implements LiveChannels {

    private static final Log logger = LogFactory.getLog(PlainLiveChannels.class);

    private final StringRedisTemplate redis;

    private final RedisMessageListenerContainer listeners;

    public PlainLiveChannels(StringRedisTemplate redis, RedisMessageListenerContainer listeners) {
        this.redis = redis;
        this.listeners = listeners;
    }

    @Override
    public void publish(String channel, String message) {
        try {
            this.redis.convertAndSend(channel, message);
        }
        catch (DataAccessException ex) {
            logger.debug("Live message on " + channel + " not sent: " + ex.getMessage());
        }
    }

    @Override
    public AutoCloseable subscribe(String channel, Consumer<String> receiver) {
        ChannelTopic topic = new ChannelTopic(channel);
        MessageListener listener = (message, pattern) -> receiver.accept(new String(message.getBody(), StandardCharsets.UTF_8));
        this.listeners.addMessageListener(listener, topic);
        return () -> this.listeners.removeMessageListener(listener, topic);
    }
}
