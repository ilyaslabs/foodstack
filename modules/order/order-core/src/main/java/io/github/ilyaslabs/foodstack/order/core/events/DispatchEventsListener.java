package io.github.ilyaslabs.foodstack.order.core.events;

import io.github.ilyaslabs.foodstack.dispatch.api.events.DeliveryCompleted;
import io.github.ilyaslabs.foodstack.dispatch.api.events.DeliveryHandedBack;
import io.github.ilyaslabs.foodstack.dispatch.api.events.DeliveryPickedUp;
import io.github.ilyaslabs.foodstack.dispatch.api.events.RiderAssigned;
import io.github.ilyaslabs.foodstack.dispatch.api.events.RiderUnassigned;
import io.github.ilyaslabs.foodstack.order.api.OrderStatus;
import io.github.ilyaslabs.foodstack.order.core.application.Actor;
import io.github.ilyaslabs.foodstack.order.core.application.OrderLifecycleService;
import io.github.ilyaslabs.foodstack.platform.core.EventTypes;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import io.github.ilyaslabs.foodstack.platform.kafka.CloudEvents;
import io.github.ilyaslabs.foodstack.platform.mongo.InboxGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.kafka.annotation.KafkaListener;

/**
 * Moves delivery orders on from dispatch events (TDD 5.6): the rider shown on the order, picked up,
 * delivered, and support handing a delivery back to the store. Each event is applied once (inbox); an
 * event that no longer fits the order (cancelled meanwhile) is logged and dropped.
 */
public class DispatchEventsListener {

    static final String CONSUMER = "order-dispatch";

    private static final Logger log = LoggerFactory.getLogger(DispatchEventsListener.class);

    private static final String ASSIGNED = EventTypes.type("dispatch", RiderAssigned.class);

    private static final String UNASSIGNED = EventTypes.type("dispatch", RiderUnassigned.class);

    private static final String PICKED_UP = EventTypes.type("dispatch", DeliveryPickedUp.class);

    private static final String COMPLETED = EventTypes.type("dispatch", DeliveryCompleted.class);

    private static final String HANDED_BACK = EventTypes.type("dispatch", DeliveryHandedBack.class);

    private final OrderLifecycleService lifecycle;

    private final InboxGuard inbox;

    private final JsonMapper jsonMapper;

    public DispatchEventsListener(OrderLifecycleService lifecycle, InboxGuard inbox, JsonMapper jsonMapper) {
        this.lifecycle = lifecycle;
        this.inbox = inbox;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(concurrency = "${foodstack.kafka.concurrency.order:${foodstack.kafka.listener-concurrency:3}}",
            id = "${foodstack.namespace:foodstack}-order-dispatch", groupId = "${foodstack.namespace:foodstack}-order",
            topics = "${foodstack.namespace:foodstack}.dispatch.events", properties = "auto.offset.reset=earliest")
    public void onEvent(String value) {
        CloudEvents.Envelope event = CloudEvents.parse(value, this.jsonMapper);
        String type = event.type();
        if (ASSIGNED.equals(type)) {
            RiderAssigned e = event.dataAs(RiderAssigned.class, this.jsonMapper);
            this.inbox.processOnce(event.id(), CONSUMER, () -> this.lifecycle.riderAssigned(e.delivery().orderId(), e.riderId(),
                    e.riderName(), e.vehicle(), e.actor()));
        }
        else if (UNASSIGNED.equals(type)) {
            RiderUnassigned e = event.dataAs(RiderUnassigned.class, this.jsonMapper);
            this.inbox.processOnce(event.id(), CONSUMER,
                    () -> this.lifecycle.riderUnassigned(e.delivery().orderId(), e.riderId(), e.reason(), e.actor()));
        }
        else if (PICKED_UP.equals(type)) {
            DeliveryPickedUp e = event.dataAs(DeliveryPickedUp.class, this.jsonMapper);
            this.inbox.processOnce(event.id(), CONSUMER,
                    () -> tolerate(e.delivery().orderId(), () -> this.lifecycle.pickedUp(e.delivery().orderId(), Actor.fromEvent(e.actor()))));
        }
        else if (COMPLETED.equals(type)) {
            DeliveryCompleted e = event.dataAs(DeliveryCompleted.class, this.jsonMapper);
            this.inbox.processOnce(event.id(), CONSUMER, () -> tolerate(e.delivery().orderId(), () -> {
                Actor actor = Actor.fromEvent(e.actor());
                String orderId = e.delivery().orderId();
                // a rider who forgot to tap "picked up" still delivered it
                if (this.lifecycle.require(orderId).status() != OrderStatus.PICKED_UP) {
                    this.lifecycle.pickedUp(orderId, actor);
                }
                this.lifecycle.delivered(orderId, actor);
            }));
        }
        else if (HANDED_BACK.equals(type)) {
            DeliveryHandedBack e = event.dataAs(DeliveryHandedBack.class, this.jsonMapper);
            this.inbox.processOnce(event.id(), CONSUMER,
                    () -> this.lifecycle.handedBack(e.delivery().orderId(), e.reason(), e.actor()));
        }
    }

    private static void tolerate(String orderId, Runnable step) {
        try {
            step.run();
        }
        catch (ModuleException.Conflict | ModuleException.NotFound | ModuleException.BadRequest ex) {
            log.info("Dispatch step for order {} not applied: {}", orderId, ex.getMessage());
        }
    }
}
