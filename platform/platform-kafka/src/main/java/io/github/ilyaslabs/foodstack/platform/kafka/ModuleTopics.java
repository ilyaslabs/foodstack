package io.github.ilyaslabs.foodstack.platform.kafka;

import io.github.ilyaslabs.foodstack.platform.core.EventTypes;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;

import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Topic names of a module inside a namespace, and the topics {@link KafkaAdmin} creates for it:
 * {@code <ns>.<module>.events} (7 days) and its dead-letter topic {@code .dlt} (30 days).
 */
public final class ModuleTopics {

    private static final String SEVEN_DAYS = String.valueOf(7L * 24 * 3600 * 1000);

    private static final String THIRTY_DAYS = String.valueOf(30L * 24 * 3600 * 1000);

    private ModuleTopics() {
    }

    /** {@code foodstack.order.events} in the default namespace. */
    public static String events(String namespace, String module) {
        return namespace + "." + EventTypes.requireModule(module) + ".events";
    }

    public static String deadLetter(String namespace, String module) {
        return events(namespace, module) + ".dlt";
    }

    /** The topics a publishing module needs; register the result as a bean. */
    public static KafkaAdmin.NewTopics create(String namespace, String module, FoodstackKafkaProperties properties) {
        NewTopic events = TopicBuilder.name(events(namespace, module))
            .partitions(properties.getTopicPartitions())
            .replicas(properties.getReplicationFactor())
            .config(TopicConfig.RETENTION_MS_CONFIG, SEVEN_DAYS)
            .build();
        NewTopic deadLetter = TopicBuilder.name(deadLetter(namespace, module))
            .partitions(properties.getTopicPartitions())
            .replicas(properties.getReplicationFactor())
            .config(TopicConfig.RETENTION_MS_CONFIG, THIRTY_DAYS)
            .build();
        return new KafkaAdmin.NewTopics(events, deadLetter);
    }
}
