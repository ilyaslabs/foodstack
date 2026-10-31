package io.github.ilyaslabs.foodstack.platform.kafka;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongo;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.bson.Document;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.context.SmartLifecycle;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Publishes a module's outbox to its Kafka topic (TDD 7.2).
 * <p>
 * One replica at a time holds the module's leader lease and polls {@code NEW} rows in id order,
 * sends them keyed by aggregate, waits for {@code acks=all} and marks them {@code SENT} (removed
 * by a TTL index after 7 days). When a send fails, only the rows before the first failure are
 * marked, so later events of the same aggregate are sent again after it: ordering per aggregate
 * is kept, at the price of duplicates that consumers drop through their inbox.
 */
public class OutboxRelay implements SmartLifecycle {

    private static final Log logger = LogFactory.getLog(OutboxRelay.class);

    private final String module;

    private final String collection;

    private final String topic;

    private final MongoTemplate template;

    private final KafkaTemplate<String, String> kafka;

    private final RedisLeaderLock leaderLock;

    private final JsonMapper jsonMapper;

    private final FoodstackKafkaProperties.Relay settings;

    private final Clock clock;

    private volatile boolean running;

    private Thread worker;

    public OutboxRelay(ModuleMongo mongo, String topic, KafkaTemplate<String, String> kafka,
            RedisLeaderLock leaderLock, JsonMapper jsonMapper, FoodstackKafkaProperties properties, Clock clock) {
        this.module = mongo.module();
        this.collection = mongo.collection("outbox");
        this.topic = topic;
        this.template = mongo.template();
        this.kafka = kafka;
        this.leaderLock = leaderLock;
        this.jsonMapper = jsonMapper;
        this.settings = properties.getRelay();
        this.clock = clock;
    }

    @Override
    public void start() {
        this.running = true;
        this.worker = Thread.ofVirtual().name("outbox-relay-" + this.module).start(this::loop);
    }

    @Override
    public void stop() {
        this.running = false;
        if (this.worker != null) {
            this.worker.interrupt();
            try {
                this.worker.join(this.settings.getSendTimeout().toMillis());
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            this.leaderLock.release();
        }
        catch (RuntimeException ex) {
            logger.debug("Could not release outbox leader lock of " + this.module, ex);
        }
    }

    @Override
    public boolean isRunning() {
        return this.running;
    }

    private void loop() {
        while (this.running) {
            int published = 0;
            try {
                if (this.leaderLock.acquireOrRenew()) {
                    published = publishBatch();
                }
            }
            catch (RuntimeException ex) {
                logger.warn("Outbox relay of module '" + this.module + "' failed; retrying", ex);
            }
            if (published < this.settings.getBatchSize()) {
                try {
                    Thread.sleep(this.settings.getPollInterval());
                }
                catch (InterruptedException ex) {
                    return;
                }
            }
        }
    }

    /**
     * Sends one batch of pending rows.
     * @return how many rows were marked sent
     */
    public int publishBatch() {
        Query pending = Query.query(Criteria.where("status").is(OutboxWriter.STATUS_NEW))
            .with(Sort.by("_id"))
            .limit(this.settings.getBatchSize());
        List<Document> rows = this.template.find(pending, Document.class, this.collection);
        if (rows.isEmpty()) {
            return 0;
        }
        List<CompletableFuture<?>> sends = new ArrayList<>(rows.size());
        for (Document row : rows) {
            sends.add(this.kafka.send(this.topic, row.getString("subject"), CloudEvents.fromOutbox(row, this.jsonMapper)));
        }
        List<String> sent = new ArrayList<>();
        String failedId = null;
        for (int i = 0; i < rows.size(); i++) {
            try {
                sends.get(i).get(this.settings.getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
                sent.add(rows.get(i).getString("_id"));
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                failedId = rows.get(i).getString("_id");
                break;
            }
            catch (Exception ex) {
                failedId = rows.get(i).getString("_id");
                logger.warn("Could not publish event " + failedId + " of module '" + this.module + "'", ex);
                break;
            }
        }
        if (!sent.isEmpty()) {
            this.template.updateMulti(Query.query(Criteria.where("_id").in(sent)),
                    new Update().set("status", OutboxWriter.STATUS_SENT).set("sentAt", Date.from(this.clock.instant())),
                    this.collection);
        }
        if (failedId != null) {
            this.template.updateFirst(Query.query(Criteria.where("_id").is(failedId)), new Update().inc("attempts", 1),
                    this.collection);
        }
        return sent.size();
    }
}
