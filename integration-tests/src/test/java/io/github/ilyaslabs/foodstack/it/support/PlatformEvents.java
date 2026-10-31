package io.github.ilyaslabs.foodstack.it.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import io.github.ilyaslabs.foodstack.platform.test.FoodstackContainers;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

import static org.awaitility.Awaitility.await;

/** Reads a module's event topic from the beginning, to check what the outbox relays published. */
public final class PlatformEvents {

    private PlatformEvents() {
    }

    /**
     * Waits until an event of {@code type} whose JSON contains {@code text} appears on
     * {@code <namespace>.<module>.events}, and returns its CloudEvent JSON.
     */
    public static String awaitEvent(FoodstackPlatform platform, String module, String type, String text) {
        String topic = platform.namespace() + "." + module + ".events";
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, FoodstackContainers.kafka().getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, FoodstackContainers.uniqueName());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(topic));
            List<String> matches = new ArrayList<>();
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (record.value().contains("\"type\":\"" + type + "\"") && record.value().contains(text)) {
                        matches.add(record.value());
                    }
                }
                return !matches.isEmpty();
            });
            return matches.getFirst();
        }
    }
}
