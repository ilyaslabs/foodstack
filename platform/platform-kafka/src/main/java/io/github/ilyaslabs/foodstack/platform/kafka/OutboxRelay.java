package io.github.ilyaslabs.foodstack.platform.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongo;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.kafka.clients.producer.ProducerRecord;
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
 * Every replica relays, each over the outbox {@link BucketLeases buckets} it holds (TDD 7.5): it
 * polls {@code NEW} rows of its buckets in id order, sends them keyed by aggregate, waits for {@code acks=all} and marks them {@code SENT} (removed
 * by a TTL index after 7 days). When a send fails, only the rows before the first failure are
 * marked, so later events of the same aggregate are sent again after it: ordering per aggregate
 * is kept, at the price of duplicates that consumers drop through their inbox.
 */
public class OutboxRelay implements SmartLifecycle {

    private static final Log logger = LogFactory.getLog(OutboxRelay.class);

    /** W3C trace context header, also a CloudEvents distributed-tracing attribute. */
    static final String TRACEPARENT = "traceparent";

    private final String module;

    private final String collection;

    private final String topic;

    private final MongoTemplate template;

    private final KafkaTemplate<String, String> kafka;

    private final BucketLeases leases;

    private final JsonMapper jsonMapper;

    private final FoodstackKafkaProperties.Relay settings;

    private final Clock clock;

    private volatile boolean running;

    private volatile Backlog backlog = new Backlog(0, 0, Instant.EPOCH);

    private Counter published;

    /** Pending rows and the age of the oldest, read at most every 10 s however often it is scraped. */
    private record Backlog(long pending, double oldestSeconds, Instant readAt) {
    }

    private Thread worker;

    public OutboxRelay(ModuleMongo mongo, String topic, KafkaTemplate<String, String> kafka,
            BucketLeases leases, JsonMapper jsonMapper, FoodstackKafkaProperties properties, Clock clock) {
        this.module = mongo.module();
        this.collection = mongo.collection("outbox");
        this.topic = topic;
        this.template = mongo.template();
        this.kafka = kafka;
        this.leases = leases;
        this.jsonMapper = jsonMapper;
        this.settings = properties.getRelay();
        this.clock = clock;
    }

    /**
     * Meters of this module's outbox (TDD 18.5): {@code foodstack.outbox.pending} and
     * {@code foodstack.outbox.oldest.age} (seconds) are the same in every replica, so dashboards take
     * the max; {@code foodstack.outbox.published} counts what this replica sent.
     */
    public void bindTo(MeterRegistry meters) {
        Tags tags = Tags.of("module", this.module);
        Gauge.builder("foodstack.outbox.pending", this, (relay) -> relay.backlog().pending())
            .description("Outbox rows not yet published to Kafka")
            .tags(tags)
            .register(meters);
        Gauge.builder("foodstack.outbox.oldest.age", this, (relay) -> relay.backlog().oldestSeconds())
            .description("Seconds the oldest unpublished outbox row has waited")
            .baseUnit("seconds")
            .tags(tags)
            .register(meters);
        this.published = Counter.builder("foodstack.outbox.published")
            .description("Events this replica published from the outbox")
            .tags(tags)
            .register(meters);
    }

    private Backlog backlog() {
        Backlog current = this.backlog;
        Instant now = this.clock.instant();
        if (Duration.between(current.readAt(), now).toSeconds() < 10) {
            return current;
        }
        try {
            Query pending = Query.query(Criteria.where("status").is(OutboxWriter.STATUS_NEW));
            long count = this.template.count(pending.limit(100_000), this.collection);
            Document oldest = (count == 0) ? null
                    : this.template.findOne(Query.query(Criteria.where("status").is(OutboxWriter.STATUS_NEW))
                        .with(Sort.by("_id")), Document.class, this.collection);
            Date occurredAt = (oldest != null) ? oldest.getDate("occurredAt") : null;
            double age = (occurredAt != null) ? Math.max(0, Duration.between(occurredAt.toInstant(), now).toMillis() / 1000.0)
                    : 0;
            current = new Backlog(count, age, now);
            this.backlog = current;
        }
        catch (RuntimeException ex) {
            // keep the last reading; the database being down shows elsewhere
        }
        return current;
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
        this.leases.release();
    }

    @Override
    public boolean isRunning() {
        return this.running;
    }

    private void loop() {
        while (this.running) {
            int published = 0;
            try {
                Set<Integer> buckets = this.leases.current();
                if (!buckets.isEmpty()) {
                    published = publishBatch(buckets);
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
     * Sends one batch of pending rows of the given buckets.
     * @return how many rows were marked sent
     */
    public int publishBatch(Set<Integer> buckets) {
        Query pending = Query.query(Criteria.where("status").is(OutboxWriter.STATUS_NEW).and("bucket").in(buckets))
            .with(Sort.by("_id"))
            .limit(this.settings.getBatchSize());
        List<Document> rows = this.template.find(pending, Document.class, this.collection);
        if (rows.isEmpty()) {
            return 0;
        }
        List<CompletableFuture<?>> sends = new ArrayList<>(rows.size());
        for (Document row : rows) {
            ProducerRecord<String, String> record = new ProducerRecord<>(this.topic, row.getString("subject"),
                    CloudEvents.fromOutbox(row, this.jsonMapper));
            String traceparent = row.getString("traceparent");
            if (traceparent != null) {
                // a listener with observation on continues the writer's trace from this header
                record.headers().add(TRACEPARENT, traceparent.getBytes(StandardCharsets.US_ASCII));
            }
            sends.add(this.kafka.send(record));
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
        if (!sent.isEmpty() && this.published != null) {
            this.published.increment(sent.size());
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
