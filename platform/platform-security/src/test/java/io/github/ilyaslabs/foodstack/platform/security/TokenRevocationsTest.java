package io.github.ilyaslabs.foodstack.platform.security;

import java.time.Clock;
import java.time.Duration;

import io.github.ilyaslabs.foodstack.platform.test.FoodstackContainers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class TokenRevocationsTest {

    private LettuceConnectionFactory connectionFactory;

    private StringRedisTemplate redis;

    private String namespace;

    @BeforeEach
    void connect() {
        this.connectionFactory = new LettuceConnectionFactory(FoodstackContainers.redis().getHost(),
                FoodstackContainers.redis().getMappedPort(6379));
        this.connectionFactory.afterPropertiesSet();
        this.redis = new StringRedisTemplate(this.connectionFactory);
        this.namespace = FoodstackContainers.uniqueName();
    }

    @AfterEach
    void disconnect() {
        this.connectionFactory.destroy();
    }

    @Test
    @DisplayName("accepts every token version until the user's tokens are revoked")
    void notRevoked() {
        TokenRevocations revocations = new TokenRevocations(this.redis, this.namespace, Duration.ZERO, Clock.systemUTC());

        assertThat(revocations.isRevoked("user-1", 0)).isFalse();
    }

    @Test
    @DisplayName("rejects versions older than the revoked one, in every module sharing Redis")
    void revokedEverywhere() {
        // given
        TokenRevocations identity = new TokenRevocations(this.redis, this.namespace, Duration.ZERO, Clock.systemUTC());
        TokenRevocations otherModule = new TokenRevocations(this.redis, this.namespace, Duration.ZERO, Clock.systemUTC());

        // when
        identity.revokeBefore("user-1", 3);

        // then
        assertThat(this.redis.opsForValue().get(this.namespace + ":identity:revoked:user-1")).isEqualTo("3");
        assertThat(otherModule.isRevoked("user-1", 2)).isTrue();
        assertThat(otherModule.isRevoked("user-1", 3)).isFalse();
        assertThat(otherModule.isRevoked("user-2", 0)).isFalse();
    }
}
