package io.github.ilyaslabs.foodstack.platform.kafka;

import io.github.ilyaslabs.foodstack.platform.core.AuditEntry;
import io.github.ilyaslabs.foodstack.platform.core.AuditSink;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.kafka.core.KafkaTemplate;

/**
 * Sends audit entries to {@code <namespace>.audit}, keyed by the path written so the entries of one
 * resource stay in order; the identity module keeps them. The entry is recorded after the write has
 * happened, outside its transaction, so there is no outbox: if Kafka refuses it, the whole entry is
 * written to the application log at {@code ERROR} instead, where it can be recovered.
 */
public class KafkaAuditSink implements AuditSink {

    private static final Log logger = LogFactory.getLog(KafkaAuditSink.class);

    private final KafkaTemplate<String, String> kafka;

    private final JsonMapper json;

    private final String topic;

    public KafkaAuditSink(KafkaTemplate<String, String> kafka, JsonMapper json, String namespace) {
        this.kafka = kafka;
        this.json = json;
        this.topic = topic(namespace);
    }

    /** {@code foodstack.audit} in the default namespace. */
    public static String topic(String namespace) {
        return namespace + ".audit";
    }

    @Override
    public void record(AuditEntry entry) {
        String value;
        try {
            value = this.json.writeValueAsString(entry);
        }
        catch (RuntimeException ex) {
            logger.error("Could not write audit entry " + entry, ex);
            return;
        }
        try {
            this.kafka.send(this.topic, entry.resource(), value).whenComplete((result, failure) -> {
                if (failure != null) {
                    logger.error("Audit entry not delivered, kept here: " + value, failure);
                }
            });
        }
        catch (RuntimeException ex) {
            logger.error("Audit entry not delivered, kept here: " + value, ex);
        }
    }
}
