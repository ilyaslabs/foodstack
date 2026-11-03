package io.github.ilyaslabs.foodstack.dispatch.core.events;

import io.github.ilyaslabs.foodstack.dispatch.core.application.DispatchService;
import io.github.ilyaslabs.foodstack.order.api.events.OrderAccepted;
import io.github.ilyaslabs.foodstack.order.api.events.OrderCancelled;
import io.github.ilyaslabs.foodstack.order.api.events.OrderDelivered;
import io.github.ilyaslabs.foodstack.order.api.events.OrderFailed;
import io.github.ilyaslabs.foodstack.order.api.events.OrderPickedUp;
import io.github.ilyaslabs.foodstack.order.api.events.OrderReady;
import io.github.ilyaslabs.foodstack.order.api.events.OrderRejected;
import io.github.ilyaslabs.foodstack.platform.core.EventTypes;
import io.github.ilyaslabs.foodstack.platform.kafka.CloudEvents;
import io.github.ilyaslabs.foodstack.platform.mongo.InboxGuard;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.kafka.annotation.KafkaListener;

/**
 * Follows orders (TDD 5.6): an accepted delivery order becomes a job, a ready order starts its
 * search, a cancelled or rejected order cancels its job, and steps the store marked on the order
 * itself close the job. Steps that came from dispatch (actor {@code rider:...}) are echoes and
 * ignored. Each event is applied once (inbox).
 */
public class OrderEventsListener {

    static final String CONSUMER = "dispatch-orders";

    private static final String ACCEPTED = EventTypes.type("order", OrderAccepted.class);

    private static final String READY = EventTypes.type("order", OrderReady.class);

    private static final String CANCELLED = EventTypes.type("order", OrderCancelled.class);

    private static final String REJECTED = EventTypes.type("order", OrderRejected.class);

    private static final String FAILED = EventTypes.type("order", OrderFailed.class);

    private static final String PICKED_UP = EventTypes.type("order", OrderPickedUp.class);

    private static final String DELIVERED = EventTypes.type("order", OrderDelivered.class);

    private final DispatchService dispatch;

    private final InboxGuard inbox;

    private final JsonMapper jsonMapper;

    public OrderEventsListener(DispatchService dispatch, InboxGuard inbox, JsonMapper jsonMapper) {
        this.dispatch = dispatch;
        this.inbox = inbox;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(id = "${foodstack.namespace:foodstack}-dispatch-orders", groupId = "${foodstack.namespace:foodstack}-dispatch",
            topics = "${foodstack.namespace:foodstack}.order.events", properties = "auto.offset.reset=earliest")
    public void onEvent(String value) {
        CloudEvents.Envelope event = CloudEvents.parse(value, this.jsonMapper);
        String type = event.type();
        if (ACCEPTED.equals(type)) {
            OrderAccepted e = event.dataAs(OrderAccepted.class, this.jsonMapper);
            if (e.delivery() != null) {
                this.inbox.processOnce(event.id(), CONSUMER,
                        () -> this.dispatch.orderAccepted(e.order(), e.delivery(), e.readyBy(), e.actor()));
            }
        }
        else if (READY.equals(type)) {
            OrderReady e = event.dataAs(OrderReady.class, this.jsonMapper);
            this.inbox.processOnce(event.id(), CONSUMER, () -> this.dispatch.orderReady(e.order().orderId()));
        }
        else if (CANCELLED.equals(type)) {
            OrderCancelled e = event.dataAs(OrderCancelled.class, this.jsonMapper);
            this.inbox.processOnce(event.id(), CONSUMER, () -> this.dispatch.orderClosed(e.order().orderId(), e.reason()));
        }
        else if (REJECTED.equals(type)) {
            OrderRejected e = event.dataAs(OrderRejected.class, this.jsonMapper);
            this.inbox.processOnce(event.id(), CONSUMER, () -> this.dispatch.orderClosed(e.order().orderId(), e.reason()));
        }
        else if (FAILED.equals(type)) {
            OrderFailed e = event.dataAs(OrderFailed.class, this.jsonMapper);
            this.inbox.processOnce(event.id(), CONSUMER, () -> this.dispatch.orderClosed(e.order().orderId(), e.reason()));
        }
        else if (PICKED_UP.equals(type)) {
            OrderPickedUp e = event.dataAs(OrderPickedUp.class, this.jsonMapper);
            this.inbox.processOnce(event.id(), CONSUMER,
                    () -> this.dispatch.orderPickedUpElsewhere(e.order().orderId(), e.actor()));
        }
        else if (DELIVERED.equals(type)) {
            OrderDelivered e = event.dataAs(OrderDelivered.class, this.jsonMapper);
            this.inbox.processOnce(event.id(), CONSUMER,
                    () -> this.dispatch.orderDeliveredElsewhere(e.order().orderId(), e.actor()));
        }
    }
}
