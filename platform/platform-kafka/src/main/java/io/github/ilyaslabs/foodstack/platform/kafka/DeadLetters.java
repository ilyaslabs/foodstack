package io.github.ilyaslabs.foodstack.platform.kafka;

import org.apache.kafka.common.TopicPartition;

import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * The error handler every FoodStack listener runs with (TDD 7.1). A record whose handler keeps
 * failing is retried with a growing pause, then published unchanged to {@code <topic>.dlt} on the
 * same partition, with Spring Kafka's headers naming the consumer group, the exception and the
 * original offset, so it can be inspected and replayed. The listener then moves on, so one bad
 * record never blocks its partition.
 */
public final class DeadLetters {

    /** Header of a replayed dead letter: the consumer group meant to handle it again (see {@link ReplayFilter}). */
    public static final String REPLAY_FOR = "foodstack-replay-for";

    private DeadLetters() {
    }

    /** Whether {@code topic} is a dead-letter topic. */
    public static boolean isDeadLetterTopic(String topic) {
        return topic.endsWith(".dlt");
    }

    /** {@code foodstack.store.events.dlt} becomes {@code foodstack.store.events}. */
    public static String originalTopic(String deadLetterTopic) {
        return deadLetterTopic.substring(0, deadLetterTopic.length() - ".dlt".length());
    }

    /** {@code foodstack.store.events} becomes {@code foodstack.store.events.dlt} (see {@link ModuleTopics}). */
    public static String topicFor(String topic) {
        return topic + ".dlt";
    }

    public static DefaultErrorHandler errorHandler(KafkaOperations<?, ?> kafka, FoodstackKafkaProperties.Consumer settings) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafka,
                (record, ex) -> new TopicPartition(topicFor(record.topic()), record.partition()));
        ExponentialBackOff backOff = new ExponentialBackOff(settings.getInitialBackoff().toMillis(), 2.0);
        backOff.setMaxInterval(settings.getMaxBackoff().toMillis());
        backOff.setMaxAttempts(Math.max(settings.getAttempts() - 1, 0));
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        // A record that cannot even be read is not worth retrying
        handler.addNotRetryableExceptions(tools.jackson.core.JacksonException.class);
        return handler;
    }
}
