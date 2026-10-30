package io.github.ilyaslabs.foodstack.platform.mongo;

import java.time.Clock;
import java.time.Instant;

import io.github.ilyaslabs.foodstack.platform.core.EventTypes;
import io.github.ilyaslabs.foodstack.platform.core.Ids;
import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;
import org.bson.Document;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Appends events to the module's {@code <module>_outbox} collection (TDD 7.2).
 * <p>
 * Must be called inside the module's transaction, together with the aggregate write, so the
 * event is stored if and only if the change is. The relay publishes it to Kafka afterwards.
 */
public class OutboxWriter {

    public static final String STATUS_NEW = "NEW";

    public static final String STATUS_SENT = "SENT";

    private final String module;

    private final String collection;

    private final MongoTemplate template;

    private final JsonMapper jsonMapper;

    private final Clock clock;

    public OutboxWriter(ModuleMongo mongo, JsonMapper jsonMapper, Clock clock) {
        this.module = mongo.module();
        this.collection = mongo.collection("outbox");
        this.template = mongo.template();
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /** Stores the event; returns its id. */
    public String append(ModuleEvent event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Outbox writes must run inside the " + this.module + " transaction");
        }
        String id = Ids.newId();
        Instant now = this.clock.instant();
        Document record = new Document("_id", id)
            .append("type", EventTypes.type(this.module, event.getClass()))
            .append("source", EventTypes.source(this.module))
            .append("subject", event.subject())
            .append("occurredAt", java.util.Date.from(now))
            .append("data", this.jsonMapper.writeValueAsString(event))
            .append("status", STATUS_NEW)
            .append("attempts", 0);
        this.template.insert(record, this.collection);
        return id;
    }

    public String collection() {
        return this.collection;
    }
}
