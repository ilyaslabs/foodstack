package io.github.ilyaslabs.foodstack.settings.core.persistence;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A failed event kept for the operator (TDD 7.1, gap G1) in {@code settings_dead_letters}: a copy
 * of a record from a {@code .dlt} topic, with who failed on it and why. The event itself is stored
 * encrypted (it may hold personal data) and the row goes after 30 days, with the topic. Unsharded:
 * failed events are rare.
 *
 * @param id {@code <dlt topic>:<partition>:<offset>}, so a record read twice is stored once
 * @param module the module whose topic carried the event ({@code order} for {@code foodstack.order.events})
 * @param originalTopic where the event was published
 * @param consumerGroup the consumer group that failed on it, and the one a replay goes to
 * @param key the record key
 * @param eventType CloudEvents type, if the value could be read
 * @param eventId CloudEvents id, if the value could be read
 * @param error exception class and message, cut to 1,000 characters
 * @param valueCipher the record value, encrypted
 * @param failedAt when it was moved to the dead-letter topic
 * @param status {@code OPEN}, {@code REPLAYED} or {@code DISMISSED}
 * @param handledBy who replayed or dismissed it
 * @param handledAt when
 * @param expiresAt TTL: 30 days after it failed
 */
@Document("settings_dead_letters")
public record DeadLetterDocument(@Id String id, String module, String originalTopic, String consumerGroup, String key,
        String eventType, String eventId, String error, String valueCipher, Instant failedAt, String status,
        String handledBy, Instant handledAt, Instant expiresAt) {

    public static final String OPEN = "OPEN";

    public static final String REPLAYED = "REPLAYED";

    public static final String DISMISSED = "DISMISSED";
}
