package io.github.ilyaslabs.foodstack.platform.mongo;

import java.time.Clock;
import java.util.Date;

import org.bson.Document;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Effectively-once processing of events: a consumer handles an event id at most once (TDD 7.2).
 * <p>
 * The inbox row and the handler's writes commit in one transaction of the consuming module, so a
 * redelivered event is skipped and a failed handler leaves no inbox row.
 */
public class InboxGuard {

    private final String collection;

    private final MongoTemplate template;

    private final TransactionTemplate transaction;

    private final Clock clock;

    public InboxGuard(ModuleMongo mongo, Clock clock) {
        this.collection = mongo.collection("inbox");
        this.template = mongo.template();
        this.transaction = mongo.transactionTemplate();
        this.clock = clock;
    }

    /**
     * Runs {@code handler} unless {@code consumer} already processed {@code eventId}.
     * @return whether the handler ran
     */
    public boolean processOnce(String eventId, String consumer, Runnable handler) {
        String key = consumer + ":" + eventId;
        try {
            return Boolean.TRUE.equals(this.transaction.execute((status) -> {
                this.template.insert(new Document("_id", key).append("eventId", eventId)
                    .append("consumer", consumer)
                    .append("processedAt", Date.from(this.clock.instant())), this.collection);
                handler.run();
                return true;
            }));
        }
        catch (DuplicateKeyException ex) {
            return false;
        }
    }
}
