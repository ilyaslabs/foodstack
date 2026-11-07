package io.github.ilyaslabs.foodstack.settings.core.deadletters;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.github.ilyaslabs.foodstack.platform.kafka.DeadLetters;
import io.github.ilyaslabs.foodstack.platform.security.PiiCipher;
import io.github.ilyaslabs.foodstack.settings.core.persistence.DeadLetterDocument;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;

/**
 * Failed events for the back office (TDD 7.1, gap G1). Every {@code .dlt} topic is read into
 * {@code settings_dead_letters}; the operator looks at one, replays it to the one consumer group
 * that failed (the others already handled it, see {@code ReplayFilter}), or dismisses it. A replay
 * that fails again comes back as a new entry.
 */
public class DeadLetterService {

    static final Duration KEEP = Duration.ofDays(30);

    static final int MAX_ERROR = 1000;

    static final int PAGE = 100;

    /** A failed event as the back office lists it. */
    public record DeadLetter(String id, String module, String consumer, String eventType, String eventId, String error,
            Instant failedAt, String status, String handledBy, Instant handledAt) {
    }

    private final MongoTemplate mongo;

    private final KafkaTemplate<String, String> kafka;

    private final PiiCipher pii;

    private final JsonMapper jsonMapper;

    private final String namespace;

    private final Clock clock;

    private final Counter received;

    public DeadLetterService(MongoTemplate mongo, KafkaTemplate<String, String> kafka, PiiCipher pii,
            JsonMapper jsonMapper, String namespace, Clock clock, MeterRegistry meters) {
        this.mongo = mongo;
        this.kafka = kafka;
        this.pii = pii;
        this.jsonMapper = jsonMapper;
        this.namespace = namespace;
        this.clock = clock;
        // alert rule "dead letters" in the observability chart
        this.received = Counter.builder("foodstack.dead.letters")
            .description("Events moved to a dead-letter topic after their handler kept failing")
            .register(meters);
    }

    /** Keeps a record read from a dead-letter topic; reading it again changes nothing. */
    public void record(ConsumerRecord<String, String> record) {
        String originalTopic = header(record.headers(), KafkaHeaders.DLT_ORIGINAL_TOPIC)
            .orElse(DeadLetters.originalTopic(record.topic()));
        String group = header(record.headers(), KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP).orElse("");
        String error = header(record.headers(), KafkaHeaders.DLT_EXCEPTION_FQCN).map((type) -> type + ": ").orElse("")
                + header(record.headers(), KafkaHeaders.DLT_EXCEPTION_MESSAGE).orElse("");
        String eventType = null;
        String eventId = null;
        try {
            JsonNode event = this.jsonMapper.readTree(record.value());
            eventType = text(event, "type");
            eventId = text(event, "id");
        }
        catch (RuntimeException ex) {
            // not readable: exactly what failed; keep it as it is
        }
        Instant failedAt = Instant.ofEpochMilli(record.timestamp());
        DeadLetterDocument document = new DeadLetterDocument(record.topic() + ":" + record.partition() + ":" + record.offset(),
                module(originalTopic), originalTopic, group, record.key(), eventType, eventId,
                error.length() > MAX_ERROR ? error.substring(0, MAX_ERROR) : error,
                record.value() != null ? this.pii.encrypt(record.value()) : null, failedAt, DeadLetterDocument.OPEN, null,
                null, failedAt.plus(KEEP));
        try {
            this.mongo.insert(document);
            this.received.increment();
        }
        catch (DuplicateKeyException ex) {
            // read again after a restart
        }
    }

    /** Newest first, at most 100; {@code status} null for every status. */
    public List<DeadLetter> list(String status) {
        Query query = new Query().with(Sort.by(Sort.Direction.DESC, "failedAt")).limit(PAGE);
        if (status != null && !status.isBlank()) {
            query.addCriteria(Criteria.where("status").is(status));
        }
        return this.mongo.find(query, DeadLetterDocument.class).stream().map(this::view).toList();
    }

    /** How many are waiting for someone, for the navigation badge. */
    public long openCount() {
        return this.mongo.count(Query.query(Criteria.where("status").is(DeadLetterDocument.OPEN)), DeadLetterDocument.class);
    }

    /** The event itself, decrypted, to see what failed. */
    public String event(String id) {
        DeadLetterDocument document = find(id);
        return document.valueCipher() != null ? this.pii.decrypt(document.valueCipher()) : null;
    }

    /** Publishes the event again for the consumer group that failed on it, and marks the entry replayed. */
    public DeadLetter replay(String id, String userId) {
        DeadLetterDocument document = find(id);
        if (!DeadLetterDocument.OPEN.equals(document.status())) {
            throw new ModuleException.Conflict("This event was already " + document.status().toLowerCase(),
                    "DEAD_LETTER_HANDLED");
        }
        if (document.consumerGroup().isBlank()) {
            throw new ModuleException.Conflict("The failed consumer is unknown; this event cannot be replayed",
                    "DEAD_LETTER_NO_CONSUMER");
        }
        if (!document.originalTopic().startsWith(this.namespace + ".")) {
            throw new ModuleException.Conflict("This event belongs to another installation", "DEAD_LETTER_FOREIGN");
        }
        ProducerRecord<String, String> replay = new ProducerRecord<>(document.originalTopic(), document.key(),
                document.valueCipher() != null ? this.pii.decrypt(document.valueCipher()) : null);
        replay.headers().add(DeadLetters.REPLAY_FOR, document.consumerGroup().getBytes(StandardCharsets.UTF_8));
        try {
            this.kafka.send(replay).get(10, TimeUnit.SECONDS);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while replaying " + id, ex);
        }
        catch (ExecutionException | TimeoutException ex) {
            throw new IllegalStateException("Could not publish the replay of " + id, ex);
        }
        return handle(id, DeadLetterDocument.REPLAYED, userId);
    }

    /** Marks the entry dealt with, without replaying it. */
    public DeadLetter dismiss(String id, String userId) {
        DeadLetterDocument document = find(id);
        if (!DeadLetterDocument.OPEN.equals(document.status())) {
            throw new ModuleException.Conflict("This event was already " + document.status().toLowerCase(),
                    "DEAD_LETTER_HANDLED");
        }
        return handle(id, DeadLetterDocument.DISMISSED, userId);
    }

    private DeadLetter handle(String id, String status, String userId) {
        // only one person handles an entry
        Query open = Query.query(Criteria.where("_id").is(id).and("status").is(DeadLetterDocument.OPEN));
        boolean changed = this.mongo.updateFirst(open,
                new Update().set("status", status).set("handledBy", userId).set("handledAt", this.clock.instant()),
                DeadLetterDocument.class).getModifiedCount() == 1;
        if (!changed) {
            throw new ModuleException.Conflict("Someone else handled this event meanwhile", "DEAD_LETTER_HANDLED");
        }
        return view(find(id));
    }

    private DeadLetterDocument find(String id) {
        DeadLetterDocument document = this.mongo.findById(id, DeadLetterDocument.class);
        if (document == null) {
            throw new ModuleException.NotFound("No such failed event", "DEAD_LETTER_NOT_FOUND");
        }
        return document;
    }

    private DeadLetter view(DeadLetterDocument d) {
        return new DeadLetter(d.id(), d.module(), consumer(d.consumerGroup()), d.eventType(), d.eventId(), d.error(),
                d.failedAt(), d.status(), d.handledBy(), d.handledAt());
    }

    /** {@code foodstack.order.events} is the order module's topic. */
    private String module(String topic) {
        String rest = topic.startsWith(this.namespace + ".") ? topic.substring(this.namespace.length() + 1) : topic;
        int dot = rest.indexOf('.');
        return dot > 0 ? rest.substring(0, dot) : rest;
    }

    /** {@code foodstack-notification} is the notification module's consumer. */
    private String consumer(String group) {
        return group.startsWith(this.namespace + "-") ? group.substring(this.namespace.length() + 1) : group;
    }

    private static Optional<String> header(Headers headers, String name) {
        Header header = headers.lastHeader(name);
        return header != null ? Optional.of(new String(header.value(), StandardCharsets.UTF_8)) : Optional.empty();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isString() ? value.asString() : null;
    }
}
