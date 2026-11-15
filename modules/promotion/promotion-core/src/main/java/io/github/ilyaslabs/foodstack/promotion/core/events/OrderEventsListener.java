package io.github.ilyaslabs.foodstack.promotion.core.events;

import io.github.ilyaslabs.evl.EVL;
import io.github.ilyaslabs.evl.Flow;
import io.github.ilyaslabs.foodstack.order.api.events.OrderCancelled;
import io.github.ilyaslabs.foodstack.order.api.events.OrderConfirmed;
import io.github.ilyaslabs.foodstack.order.api.events.OrderFailed;
import io.github.ilyaslabs.foodstack.order.api.events.OrderRejected;
import io.github.ilyaslabs.foodstack.platform.core.EventTypes;
import io.github.ilyaslabs.foodstack.platform.kafka.CloudEvents;
import io.github.ilyaslabs.foodstack.platform.mongo.InboxGuard;
import io.github.ilyaslabs.foodstack.promotion.core.application.CouponService;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.kafka.annotation.KafkaListener;

/**
 * Follows orders (TDD 5.3): a confirmed order redeems its coupon and counts towards first-order
 * coupons; a cancelled, rejected or failed order gives its coupon use back. Counters are not
 * idempotent, so each event is handled once through the inbox.
 */
public class OrderEventsListener {

    static final String CONSUMER = "promotion-orders";

    private static final String CONFIRMED = EventTypes.type("order", OrderConfirmed.class);

    private static final String CANCELLED = EventTypes.type("order", OrderCancelled.class);

    private static final String REJECTED = EventTypes.type("order", OrderRejected.class);

    private static final String FAILED = EventTypes.type("order", OrderFailed.class);

    private final CouponService coupons;

    private final InboxGuard inbox;

    private final JsonMapper jsonMapper;

    public OrderEventsListener(CouponService coupons, InboxGuard inbox, JsonMapper jsonMapper) {
        this.coupons = coupons;
        this.inbox = inbox;
        this.jsonMapper = jsonMapper;
    }

    @Flow(value = "promotion-order-events", label = "Settle coupons as orders end", description = "Once per event (inbox)")
    @EVL.Job(trigger = "<ns>.order.events")
    @KafkaListener(concurrency = "${foodstack.kafka.concurrency.promotion:${foodstack.kafka.listener-concurrency:3}}",
            id = "${foodstack.namespace:foodstack}-promotion-orders", groupId = "${foodstack.namespace:foodstack}-promotion",
            topics = "${foodstack.namespace:foodstack}.order.events", properties = "auto.offset.reset=earliest")
    public void onEvent(String value) {
        CloudEvents.Envelope event = CloudEvents.parse(value, this.jsonMapper);
        String type = event.type();
        // @flow promotion-order-events>type?: Which order event?
        // @flow promotion-order-events>type?>confirmed: Redeem the coupon, count the order [when OrderConfirmed]
        if (CONFIRMED.equals(type)) {
            OrderConfirmed confirmed = event.dataAs(OrderConfirmed.class, this.jsonMapper);
            this.inbox.processOnce(event.id(), CONSUMER, () -> {
                this.coupons.redeem(confirmed.order().orderId());
                this.coupons.recordConfirmedOrder(confirmed.order().customerId());
            });
        }
        // @flow promotion-order-events>type?>ended: Release the coupon [when OrderCancelled, OrderRejected, OrderFailed]
        else if (CANCELLED.equals(type) || REJECTED.equals(type) || FAILED.equals(type)) {
            String orderId = event.subject();
            this.inbox.processOnce(event.id(), CONSUMER, () -> this.coupons.release(orderId));
        }
    }
}
