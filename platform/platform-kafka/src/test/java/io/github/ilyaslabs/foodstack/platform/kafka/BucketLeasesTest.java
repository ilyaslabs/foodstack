package io.github.ilyaslabs.foodstack.platform.kafka;

import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import io.github.ilyaslabs.foodstack.platform.core.Buckets;
import io.github.ilyaslabs.foodstack.platform.test.FoodstackContainers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class BucketLeasesTest {

    private static final Duration LEASE = Duration.ofMillis(600);

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

    private BucketLeases replica() {
        return new BucketLeases(this.redis, this.namespace, "order:outbox", LEASE, Clock.systemUTC());
    }

    @Test
    @DisplayName("one replica alone holds every bucket")
    void alone() {
        BucketLeases only = replica();
        assertThat(only.current()).hasSize(Buckets.COUNT);
    }

    @Test
    @DisplayName("a second replica gets half the buckets, with no bucket held twice")
    void shares() {
        BucketLeases first = replica();
        assertThat(first.current()).hasSize(Buckets.COUNT);
        BucketLeases second = replica();
        second.current();
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50)).untilAsserted(() -> {
            Set<Integer> a = first.current();
            Set<Integer> b = second.current();
            assertThat(a).hasSize(Buckets.COUNT / 2);
            assertThat(b).hasSize(Buckets.COUNT / 2);
            Set<Integer> all = new HashSet<>(a);
            all.addAll(b);
            assertThat(all).hasSize(Buckets.COUNT);
        });
    }

    @Test
    @DisplayName("buckets of a replica that stops move to the others")
    void takesOver() {
        BucketLeases first = replica();
        BucketLeases second = replica();
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50))
            .until(() -> first.current().size() + second.current().size() == Buckets.COUNT
                    && first.current().size() == Buckets.COUNT / 2);
        first.release();
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50))
            .until(() -> second.current().size() == Buckets.COUNT);
    }

    @Test
    @DisplayName("a replica that dies without releasing loses its buckets once its leases lapse")
    void deadReplica() {
        BucketLeases dead = replica();
        assertThat(dead.current()).hasSize(Buckets.COUNT);
        BucketLeases alive = replica();
        // the dead replica never refreshes again; its heartbeat and leases run out
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50))
            .until(() -> alive.current().size() == Buckets.COUNT);
    }
}
