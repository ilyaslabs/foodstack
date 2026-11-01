package io.github.ilyaslabs.foodstack.platform.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.platform.test.FoodstackContainers;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(classes = KafkaTestApplication.class, properties = { "spring.data.mongodb.repositories.type=none",
        "foodstack.kafka.consumer.initial-backoff=100ms", "foodstack.kafka.consumer.max-backoff=200ms" })
class DeadLettersTest {

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        FoodstackContainers.registerAll(registry);
    }

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    KafkaTestApplication.FailingListener listener;

    @Test
    @DisplayName("a record that keeps failing is tried 3 times, then lands in <topic>.dlt and the next record is handled")
    void deadLettersAfterRetries() throws Exception {
        // given
        try (KafkaConsumer<String, String> consumer = consumer()) {
            consumer.subscribe(List.of("foodstack.dltest.events.dlt"));

            // when
            this.kafka.send("foodstack.dltest.events", "key-1", "poison").get();
            this.kafka.send("foodstack.dltest.events", "key-1", "fine").get();

            // then
            List<ConsumerRecord<String, String>> dead = new ArrayList<>();
            await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(dead::add);
                assertThat(dead).isNotEmpty();
            });
            ConsumerRecord<String, String> record = dead.getFirst();
            assertThat(record.value()).isEqualTo("poison");
            assertThat(record.key()).isEqualTo("key-1");
            Header group = record.headers().lastHeader("kafka_dlt-original-consumer-group");
            assertThat(new String(group.value(), StandardCharsets.UTF_8)).isEqualTo("dltest");
            assertThat(record.headers().lastHeader("kafka_dlt-exception-message")).isNotNull();
            await().atMost(Duration.ofSeconds(30)).until(() -> this.listener.handled.contains("fine"));
            assertThat(this.listener.poisonAttempts).hasValue(3);
        }
    }

    private static KafkaConsumer<String, String> consumer() {
        return new KafkaConsumer<>(Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                FoodstackContainers.kafka().getBootstrapServers(), ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest", ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class, ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
    }
}
