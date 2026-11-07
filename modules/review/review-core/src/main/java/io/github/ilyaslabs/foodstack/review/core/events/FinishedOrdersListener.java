package io.github.ilyaslabs.foodstack.review.core.events;

import java.time.Clock;
import java.time.Instant;

import io.github.ilyaslabs.foodstack.dispatch.api.DeliverySummary;
import io.github.ilyaslabs.foodstack.dispatch.api.events.DeliveryCompleted;
import io.github.ilyaslabs.foodstack.order.api.OrderSummary;
import io.github.ilyaslabs.foodstack.order.api.events.OrderCollected;
import io.github.ilyaslabs.foodstack.order.api.events.OrderDelivered;
import io.github.ilyaslabs.foodstack.platform.core.EventTypes;
import io.github.ilyaslabs.foodstack.platform.kafka.CloudEvents;
import io.github.ilyaslabs.foodstack.review.core.application.ReviewService;

import org.springframework.kafka.annotation.KafkaListener;
import tools.jackson.databind.json.JsonMapper;

/**
 * Which orders can be rated, and by whose rider, from the order and dispatch events (PRD RV-1).
 * Both only set fields on the order's row, so redelivery and either arrival order are harmless and
 * no inbox is needed.
 */
public class FinishedOrdersListener {

    private static final String DELIVERED = EventTypes.type("order", OrderDelivered.class);

    private static final String COLLECTED = EventTypes.type("order", OrderCollected.class);

    private static final String COMPLETED = EventTypes.type("dispatch", DeliveryCompleted.class);

    private final ReviewService reviews;

    private final JsonMapper jsonMapper;

    private final Clock clock;

    public FinishedOrdersListener(ReviewService reviews, JsonMapper jsonMapper, Clock clock) {
        this.reviews = reviews;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @KafkaListener(concurrency = "${foodstack.kafka.concurrency.review:${foodstack.kafka.listener-concurrency:3}}",
            id = "${foodstack.namespace:foodstack}-review-orders", groupId = "${foodstack.namespace:foodstack}-review",
            topics = { "${foodstack.namespace:foodstack}.order.events", "${foodstack.namespace:foodstack}.dispatch.events" },
            properties = "auto.offset.reset=earliest")
    public void onEvent(String value) {
        CloudEvents.Envelope event = CloudEvents.parse(value, this.jsonMapper);
        String type = event.type();
        Instant at = (event.time() != null) ? event.time() : this.clock.instant();
        if (DELIVERED.equals(type)) {
            finished(event.dataAs(OrderDelivered.class, this.jsonMapper).order(), at);
        }
        else if (COLLECTED.equals(type)) {
            finished(event.dataAs(OrderCollected.class, this.jsonMapper).order(), at);
        }
        else if (COMPLETED.equals(type)) {
            DeliveryCompleted completed = event.dataAs(DeliveryCompleted.class, this.jsonMapper);
            DeliverySummary delivery = completed.delivery();
            this.reviews.delivered(delivery.orderId(), delivery.riderId(), delivery.riderName(), completed.riderKind());
        }
    }

    private void finished(OrderSummary order, Instant at) {
        this.reviews.orderFinished(order.orderId(), order.number(), order.customerId(), order.storeId(),
                (order.money() != null) ? order.money().brandId() : null, order.storeName(), order.storeOwnerIds(),
                order.language(), at);
    }
}
