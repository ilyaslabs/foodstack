package io.github.ilyaslabs.foodstack.platform.kafka;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongo;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.platform.test.FoodstackContainers;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(classes = KafkaTestApplication.class, properties = "spring.data.mongodb.repositories.type=none")
class OutboxRelayTest {

    record DishRenamed(String dishId, String name) implements ModuleEvent {

        @Override
        public String subject() {
            return this.dishId;
        }
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        FoodstackContainers.registerAll(registry);
    }

    @Autowired
    ModuleMongo relaytestMongo;

    @Autowired
    OutboxWriter relaytestOutbox;

    @Autowired
    ModuleEventing eventing;

    @Autowired
    JsonMapper jsonMapper;

    @Test
    @DisplayName("publishes stored events to the module topic as CloudEvents keyed by aggregate, and marks them sent")
    void publishesOutbox() {
        // given
        String topic = this.eventing.topic("relaytest");
        try (KafkaConsumer<String, String> consumer = consumer()) {
            consumer.subscribe(List.of(topic));

            // when
            String eventId = this.relaytestMongo.transactionTemplate()
                .execute((status) -> this.relaytestOutbox.append(new DishRenamed("dish-1", "Biryani")));

            // then
            List<ConsumerRecord<String, String>> received = new ArrayList<>();
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(received::add);
                assertThat(received).isNotEmpty();
            });
            ConsumerRecord<String, String> record = received.getFirst();
            assertThat(record.key()).isEqualTo("dish-1");
            CloudEvents.Envelope envelope = CloudEvents.parse(record.value(), this.jsonMapper);
            assertThat(envelope.id()).isEqualTo(eventId);
            assertThat(envelope.type()).isEqualTo("foodstack.relaytest.DishRenamed.v1");
            assertThat(envelope.source()).isEqualTo("foodstack/relaytest");
            assertThat(envelope.subject()).isEqualTo("dish-1");
            assertThat(envelope.dataAs(DishRenamed.class, this.jsonMapper)).isEqualTo(new DishRenamed("dish-1", "Biryani"));

            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                Document row = this.relaytestMongo.template().findById(eventId, Document.class, "relaytest_outbox");
                assertThat(row.getString("status")).isEqualTo("SENT");
                assertThat(row.getDate("sentAt")).isNotNull();
            });
        }
    }

    @Test
    @DisplayName("creates the module topic and its dead-letter topic with the configured partitions")
    void createsTopics() throws Exception {
        Properties config = new Properties();
        config.put("bootstrap.servers", FoodstackContainers.kafka().getBootstrapServers());
        try (AdminClient admin = AdminClient.create(config)) {
            String topic = this.eventing.topic("relaytest");
            Map<String, TopicDescription> topics = admin.describeTopics(List.of(topic, topic + ".dlt"))
                .allTopicNames()
                .get();
            assertThat(topics.get(topic).partitions()).hasSize(3);
            assertThat(topics).containsKey(topic + ".dlt");
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
