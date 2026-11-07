package io.github.ilyaslabs.foodstack.ledger.core.events;

import java.time.Clock;
import java.time.Instant;

import io.github.ilyaslabs.foodstack.dispatch.api.events.DeliveryCompleted;
import io.github.ilyaslabs.foodstack.ledger.core.application.LedgerPosting;
import io.github.ilyaslabs.foodstack.platform.core.EventTypes;
import io.github.ilyaslabs.foodstack.platform.kafka.CloudEvents;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.kafka.annotation.KafkaListener;

/** Posts operator riders' pay, tips and cash carried from dispatch's events (PRD FI-1, DR-7). */
public class DispatchEventsListener {

    private static final String COMPLETED = EventTypes.type("dispatch", DeliveryCompleted.class);

    private final LedgerPosting posting;

    private final JsonMapper jsonMapper;

    private final Clock clock;

    public DispatchEventsListener(LedgerPosting posting, JsonMapper jsonMapper, Clock clock) {
        this.posting = posting;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @KafkaListener(concurrency = "${foodstack.kafka.concurrency.ledger:${foodstack.kafka.listener-concurrency:3}}",
            id = "${foodstack.namespace:foodstack}-ledger-dispatch", groupId = "${foodstack.namespace:foodstack}-ledger",
            topics = "${foodstack.namespace:foodstack}.dispatch.events", properties = "auto.offset.reset=earliest")
    public void onEvent(String value) {
        CloudEvents.Envelope event = CloudEvents.parse(value, this.jsonMapper);
        if (COMPLETED.equals(event.type())) {
            Instant at = (event.time() != null) ? event.time() : this.clock.instant();
            this.posting.deliveryCompleted(event.dataAs(DeliveryCompleted.class, this.jsonMapper), at);
        }
    }
}
