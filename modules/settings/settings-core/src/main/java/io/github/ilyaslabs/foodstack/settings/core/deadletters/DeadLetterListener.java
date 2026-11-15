package io.github.ilyaslabs.foodstack.settings.core.deadletters;

import io.github.ilyaslabs.evl.EVL;
import io.github.ilyaslabs.evl.Flow;
import org.apache.kafka.clients.consumer.ConsumerRecord;

import org.springframework.kafka.annotation.KafkaListener;

/**
 * Reads every dead-letter topic of this installation into the back office's list (TDD 7.1). New
 * topics are picked up when the consumer refreshes its metadata (within five minutes).
 */
@EVL.Queue("dead-letter topics")
public class DeadLetterListener {

    private final DeadLetterService deadLetters;

    public DeadLetterListener(DeadLetterService deadLetters) {
        this.deadLetters = deadLetters;
    }

    @KafkaListener(id = "${foodstack.namespace:foodstack}-settings-dead-letters",
            groupId = "${foodstack.namespace:foodstack}-settings-dead-letters",
            topicPattern = "${foodstack.namespace:foodstack}\\.([a-z]+\\.events|audit)\\.dlt",
            properties = { "auto.offset.reset=earliest", "metadata.max.age.ms=60000" })
    @Flow(value = "settings-on-dead-letter", label = "Collect failed events", description = "Every module's dead-letter topic")
    @EVL.Job(trigger = "<module>.events.dlt")
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        this.deadLetters.record(record);
    }
}
