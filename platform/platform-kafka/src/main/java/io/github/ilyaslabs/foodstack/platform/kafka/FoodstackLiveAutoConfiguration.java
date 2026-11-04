package io.github.ilyaslabs.foodstack.platform.kafka;

import io.lettuce.core.cluster.RedisClusterClient;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * One {@link LiveChannels} per process, shared by every module (TDD 8.3, 7.5). Which Redis it
 * talks to is plain Spring Boot configuration: {@code spring.data.redis.url} (one server),
 * {@code spring.data.redis.sentinel.*} (failover) or {@code spring.data.redis.cluster.nodes}
 * (Redis Cluster, which switches live messages to sharded pub/sub).
 */
@AutoConfiguration(after = DataRedisAutoConfiguration.class)
@ConditionalOnBean({ RedisConnectionFactory.class, StringRedisTemplate.class })
public final class FoodstackLiveAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    LiveChannels foodstackLiveChannels(RedisConnectionFactory connectionFactory, StringRedisTemplate redis) {
        if (connectionFactory instanceof LettuceConnectionFactory lettuce && lettuce.isClusterAware()
                && lettuce.getNativeClient() instanceof RedisClusterClient cluster) {
            return new ShardedLiveChannels(cluster);
        }
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.afterPropertiesSet();
        container.start();
        return new ClosingPlainLiveChannels(redis, container);
    }

    /** Stops its listener container with the context. */
    static final class ClosingPlainLiveChannels extends PlainLiveChannels implements DisposableBean {

        private final RedisMessageListenerContainer container;

        ClosingPlainLiveChannels(StringRedisTemplate redis, RedisMessageListenerContainer container) {
            super(redis, container);
            this.container = container;
        }

        @Override
        public void destroy() throws Exception {
            this.container.destroy();
        }
    }
}
