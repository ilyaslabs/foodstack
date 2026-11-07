package io.github.ilyaslabs.foodstack.platform.kafka;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;

import org.springframework.kafka.listener.adapter.RecordFilterStrategy;
import org.springframework.kafka.support.KafkaUtils;

/**
 * Lets a replayed dead letter reach only the consumer group that failed on it (TDD 7.1). A replay
 * is the original record published again to its topic with {@link DeadLetters#REPLAY_FOR}; every
 * other group reading that topic already handled the record, so their listeners skip it.
 */
public class ReplayFilter implements RecordFilterStrategy<Object, Object> {

    @Override
    public boolean filter(ConsumerRecord<Object, Object> record) {
        Header header = record.headers().lastHeader(DeadLetters.REPLAY_FOR);
        // a replay that failed again keeps the header into the dead-letter topic; that one is for everyone
        if (header == null || DeadLetters.isDeadLetterTopic(record.topic())) {
            return false;
        }
        String target = new String(header.value(), StandardCharsets.UTF_8);
        // the container sets the group on the consumer thread before calling the listener
        return !target.equals(KafkaUtils.getConsumerGroupId());
    }
}
