package io.github.ilyaslabs.foodstack.platform.kafka;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import io.github.ilyaslabs.foodstack.platform.core.AuditEntry;
import io.github.ilyaslabs.foodstack.platform.core.AuditSink;
import io.github.ilyaslabs.foodstack.platform.test.FoodstackContainers;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** The audit sink against a real Kafka (the lowest layer, TDD 15.3). */
@SpringBootTest(classes = KafkaTestApplication.class, properties = "spring.data.mongodb.repositories.type=none")
class KafkaAuditSinkTest {

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        FoodstackContainers.registerAll(registry);
    }

    @Autowired
    AuditSink sink;

    @Autowired
    JsonMapper jsonMapper;

    @org.springframework.beans.factory.annotation.Value("${foodstack.namespace}")
    String namespace;

    @Test
    @DisplayName("is the default audit sink and sends each entry as JSON to the audit topic, keyed by the path written")
    void sends() {
        assertThat(this.sink).isInstanceOf(KafkaAuditSink.class);
        String resource = "/api/v1/stores/" + FoodstackContainers.uniqueName();
        AuditEntry entry = new AuditEntry("e1", Instant.parse("2026-11-06T12:00:00Z"), "admin-1", null, List.of("ADMIN"),
                "stores", "PUT /api/v1/stores/{storeId}", resource, 200, "{\"name\":\"Spice\"}", "{\"version\":2}", "10.0.0.1",
                "trace-1");

        this.sink.record(entry);

        try (KafkaConsumer<String, String> consumer = consumer()) {
            consumer.subscribe(List.of(KafkaAuditSink.topic(this.namespace)));
            List<ConsumerRecord<String, String>> found = new ArrayList<>();
            long deadline = System.currentTimeMillis() + 30_000;
            while (found.isEmpty() && System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach((r) -> {
                    if (resource.equals(r.key())) {
                        found.add(r);
                    }
                });
            }
            assertThat(found).hasSize(1);
            assertThat(this.jsonMapper.readValue(found.getFirst().value(), AuditEntry.class)).isEqualTo(entry);
        }
    }

    private static KafkaConsumer<String, String> consumer() {
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, FoodstackContainers.kafka().getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, FoodstackContainers.uniqueName());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer());
    }
}
