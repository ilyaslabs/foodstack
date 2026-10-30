package io.github.ilyaslabs.foodstack.platform.kafka;

import java.time.Duration;

import io.github.ilyaslabs.foodstack.platform.test.FoodstackContainers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class RedisLeaderLockTest {

    private LettuceConnectionFactory connectionFactory;

    private StringRedisTemplate redis;

    private String key;

    @BeforeEach
    void connect() {
        this.connectionFactory = new LettuceConnectionFactory(FoodstackContainers.redis().getHost(),
                FoodstackContainers.redis().getMappedPort(6379));
        this.connectionFactory.afterPropertiesSet();
        this.redis = new StringRedisTemplate(this.connectionFactory);
        this.key = FoodstackContainers.uniqueName() + ":order:outbox:leader";
    }

    @AfterEach
    void disconnect() {
        this.connectionFactory.destroy();
    }

    @Test
    @DisplayName("lets one instance hold the lease and renew it while others wait")
    void oneLeader() {
        // given
        RedisLeaderLock first = new RedisLeaderLock(this.redis, this.key, Duration.ofSeconds(10));
        RedisLeaderLock second = new RedisLeaderLock(this.redis, this.key, Duration.ofSeconds(10));

        // then
        assertThat(first.acquireOrRenew()).isTrue();
        assertThat(second.acquireOrRenew()).isFalse();
        assertThat(first.acquireOrRenew()).isTrue();
    }

    @Test
    @DisplayName("hands the lease over once the holder releases it")
    void release() {
        // given
        RedisLeaderLock first = new RedisLeaderLock(this.redis, this.key, Duration.ofSeconds(10));
        RedisLeaderLock second = new RedisLeaderLock(this.redis, this.key, Duration.ofSeconds(10));
        first.acquireOrRenew();

        // when
        second.release();
        boolean stolen = second.acquireOrRenew();
        first.release();

        // then
        assertThat(stolen).isFalse();
        assertThat(second.acquireOrRenew()).isTrue();
    }

    @Test
    @DisplayName("frees the lease when the holder stops renewing it")
    void expires() throws InterruptedException {
        // given
        RedisLeaderLock first = new RedisLeaderLock(this.redis, this.key, Duration.ofMillis(200));
        RedisLeaderLock second = new RedisLeaderLock(this.redis, this.key, Duration.ofMillis(200));
        first.acquireOrRenew();

        // when
        Thread.sleep(400);

        // then
        assertThat(second.acquireOrRenew()).isTrue();
    }
}
