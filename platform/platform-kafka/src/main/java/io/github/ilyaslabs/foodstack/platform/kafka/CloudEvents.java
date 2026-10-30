package io.github.ilyaslabs.foodstack.platform.kafka;

import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import org.bson.Document;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * CloudEvents 1.0 structured JSON envelope of every Kafka message (TDD 7.1).
 */
public final class CloudEvents {

    public static final String SPEC_VERSION = "1.0";

    private CloudEvents() {
    }

    /** The envelope of an outbox record written by {@code OutboxWriter}. */
    static String fromOutbox(Document record, JsonMapper jsonMapper) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("specversion", SPEC_VERSION);
        envelope.put("id", record.getString("_id"));
        envelope.put("source", record.getString("source"));
        envelope.put("type", record.getString("type"));
        envelope.put("subject", record.getString("subject"));
        envelope.put("time", record.get("occurredAt", Date.class).toInstant().toString());
        envelope.put("datacontenttype", "application/json");
        if (record.getString("traceparent") != null) {
            envelope.put("traceparent", record.getString("traceparent"));
        }
        envelope.put("data", jsonMapper.readTree(record.getString("data")));
        return jsonMapper.writeValueAsString(envelope);
    }

    /** Parses a structured CloudEvent. */
    public static Envelope parse(String json, JsonMapper jsonMapper) {
        JsonNode node = jsonMapper.readTree(json);
        return new Envelope(node.path("id").asString(), node.path("source").asString(), node.path("type").asString(),
                node.path("subject").asString(), Instant.parse(node.path("time").asString()), node.path("data"));
    }

    /** A received event; {@code data} is the event record as JSON. */
    public record Envelope(String id, String source, String type, String subject, Instant time, JsonNode data) {

        public <T> T dataAs(Class<T> type, JsonMapper jsonMapper) {
            return jsonMapper.treeToValue(this.data, type);
        }
    }
}
