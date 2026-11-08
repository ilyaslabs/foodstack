package io.github.ilyaslabs.foodstack.platform.kafka;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import io.github.ilyaslabs.foodstack.platform.core.Buckets;
import io.github.ilyaslabs.foodstack.platform.test.FoodstackContainers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The same code on Redis Cluster (TDD 7.5): a one-node cluster owning every slot is enough to
 * prove the client switches to sharded pub/sub and that the work leases use one key per command.
 */
class RedisClusterLiveTest {

    private static GenericContainer<?> cluster;

    private static LettuceConnectionFactory connectionFactory;

    @BeforeAll
    static void startCluster() throws Exception {
        // The node tells clients its own address and port (CLUSTER SLOTS), so it listens on the same
        // port inside and outside the container and names 127.0.0.1. A published port works on Linux
        // and on Docker Desktop alike; host networking reaches only Docker Desktop's own VM.
        int port = freePort();
        cluster = new GenericContainer<>(FoodstackContainers.REDIS_IMAGE).withExposedPorts(port)
            .withCreateContainerCmdModifier((cmd) -> cmd.getHostConfig()
                .withPortBindings(new PortBinding(Ports.Binding.bindPort(port), new ExposedPort(port))))
            .withCommand("redis-server", "--port", String.valueOf(port), "--cluster-enabled", "yes", "--cluster-announce-ip",
                    "127.0.0.1", "--save", "", "--appendonly", "no")
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));
        cluster.start();
        cluster.execInContainer("redis-cli", "-p", String.valueOf(port), "cluster", "addslotsrange", "0", "16383");
        await().atMost(Duration.ofSeconds(30)).until(() -> cluster
            .execInContainer("redis-cli", "-p", String.valueOf(port), "cluster", "info")
            .getStdout()
            .contains("cluster_state:ok"));
        connectionFactory = new LettuceConnectionFactory(new RedisClusterConfiguration(List.of("127.0.0.1:" + port)));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @AfterAll
    static void stopCluster() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
        if (cluster != null) {
            cluster.stop();
        }
    }

    @Test
    @DisplayName("on Redis Cluster live messages use sharded pub/sub, chosen by configuration alone")
    void shardedPubSub() throws Exception {
        LiveChannels live = new FoodstackLiveAutoConfiguration().foodstackLiveChannels(connectionFactory,
                new StringRedisTemplate(connectionFactory));
        assertThat(live).isInstanceOf(ShardedLiveChannels.class);
        List<String> received = new CopyOnWriteArrayList<>();
        AutoCloseable subscription = live.subscribe("fs:rider:rt:cell:486_1017", received::add);
        AutoCloseable other = live.subscribe("fs:rider:rt:cell:1_1", (m) -> received.add("wrong:" + m));

        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
            live.publish("fs:rider:rt:cell:486_1017", "{\"riderId\":\"r1\"}");
            assertThat(received).contains("{\"riderId\":\"r1\"}").noneMatch((m) -> m.startsWith("wrong:"));
        });
        subscription.close();
        other.close();
        ((ShardedLiveChannels) live).destroy();
    }

    @Test
    @DisplayName("work bucket leases run unchanged on Redis Cluster")
    void leases() {
        StringRedisTemplate redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        BucketLeases leases = new BucketLeases(redis, "fs-cluster", "order:outbox", Duration.ofSeconds(5), Clock.systemUTC());
        assertThat(leases.current()).hasSize(Buckets.COUNT);
        leases.release();
    }
}
