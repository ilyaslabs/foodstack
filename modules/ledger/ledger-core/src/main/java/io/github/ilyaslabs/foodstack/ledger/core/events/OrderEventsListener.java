package io.github.ilyaslabs.foodstack.ledger.core.events;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;

import io.github.ilyaslabs.foodstack.ledger.core.application.CreditLimits;
import io.github.ilyaslabs.foodstack.ledger.core.application.LedgerPosting;
import io.github.ilyaslabs.foodstack.order.api.OrderSummary;
import io.github.ilyaslabs.foodstack.order.api.events.OrderCancelled;
import io.github.ilyaslabs.foodstack.order.api.events.OrderCollected;
import io.github.ilyaslabs.foodstack.order.api.events.OrderDelivered;
import io.github.ilyaslabs.foodstack.order.api.events.OrderFailed;
import io.github.ilyaslabs.foodstack.order.api.events.OrderPlaced;
import io.github.ilyaslabs.foodstack.order.api.events.OrderRefunded;
import io.github.ilyaslabs.foodstack.order.api.events.OrderRejected;
import io.github.ilyaslabs.foodstack.platform.core.EventTypes;
import io.github.ilyaslabs.foodstack.platform.kafka.CloudEvents;

import org.springframework.kafka.annotation.KafkaListener;
import tools.jackson.databind.json.JsonMapper;

/**
 * Posts orders to the ledger from the order module's events (PRD FI-1). Every posting has an id
 * from its event, so a redelivered event changes nothing and no inbox is needed.
 */
public class OrderEventsListener {

    private static final String PLACED = EventTypes.type("order", OrderPlaced.class);

    private static final String DELIVERED = EventTypes.type("order", OrderDelivered.class);

    private static final String COLLECTED = EventTypes.type("order", OrderCollected.class);

    private static final String REFUNDED = EventTypes.type("order", OrderRefunded.class);

    private static final Set<String> CLOSED = Set.of(EventTypes.type("order", OrderCancelled.class),
            EventTypes.type("order", OrderRejected.class), EventTypes.type("order", OrderFailed.class));

    private final LedgerPosting posting;

    private final JsonMapper jsonMapper;

    private final Clock clock;

    private final CreditLimits creditLimits;

    public OrderEventsListener(LedgerPosting posting, JsonMapper jsonMapper, Clock clock) {
        this(posting, jsonMapper, clock, null);
    }

    /** @param creditLimits checks a direct-collecting branch's credit limit after its balance moves, or {@code null} */
    public OrderEventsListener(LedgerPosting posting, JsonMapper jsonMapper, Clock clock, CreditLimits creditLimits) {
        this.posting = posting;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.creditLimits = creditLimits;
    }

    @KafkaListener(concurrency = "${foodstack.kafka.concurrency.ledger:${foodstack.kafka.listener-concurrency:3}}",
            id = "${foodstack.namespace:foodstack}-ledger-orders", groupId = "${foodstack.namespace:foodstack}-ledger",
            topics = "${foodstack.namespace:foodstack}.order.events", properties = "auto.offset.reset=earliest")
    public void onEvent(String value) {
        CloudEvents.Envelope event = CloudEvents.parse(value, this.jsonMapper);
        String type = event.type();
        Instant at = (event.time() != null) ? event.time() : this.clock.instant();
        if (PLACED.equals(type)) {
            this.posting.orderPlaced(event.dataAs(OrderPlaced.class, this.jsonMapper).order());
        }
        else if (DELIVERED.equals(type)) {
            OrderSummary order = event.dataAs(OrderDelivered.class, this.jsonMapper).order();
            this.posting.orderDone(order, at);
            checkCredit(order.storeId());
        }
        else if (COLLECTED.equals(type)) {
            OrderSummary order = event.dataAs(OrderCollected.class, this.jsonMapper).order();
            this.posting.orderDone(order, at);
            checkCredit(order.storeId());
        }
        else if (REFUNDED.equals(type)) {
            OrderRefunded refunded = event.dataAs(OrderRefunded.class, this.jsonMapper);
            this.posting.orderRefunded(event.id(), refunded, at);
            checkCredit(refunded.order().storeId());
        }
        else if (CLOSED.contains(type)) {
            this.posting.orderClosed(event.subject());
        }
    }

    private void checkCredit(String storeId) {
        if (this.creditLimits != null) {
            this.creditLimits.check(storeId);
        }
    }
}
