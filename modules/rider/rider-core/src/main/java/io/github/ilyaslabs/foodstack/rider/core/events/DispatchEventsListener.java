package io.github.ilyaslabs.foodstack.rider.core.events;

import java.time.Clock;

import io.github.ilyaslabs.foodstack.dispatch.api.DeliverySummary;
import io.github.ilyaslabs.foodstack.dispatch.api.events.DeliveryCancelled;
import io.github.ilyaslabs.foodstack.dispatch.api.events.DeliveryCompleted;
import io.github.ilyaslabs.foodstack.dispatch.api.events.DeliveryHandedBack;
import io.github.ilyaslabs.foodstack.dispatch.api.events.DeliveryPickedUp;
import io.github.ilyaslabs.foodstack.dispatch.api.events.RiderAssigned;
import io.github.ilyaslabs.foodstack.dispatch.api.events.RiderOffered;
import io.github.ilyaslabs.foodstack.dispatch.api.events.RiderUnassigned;
import io.github.ilyaslabs.foodstack.platform.core.EventTypes;
import io.github.ilyaslabs.foodstack.platform.kafka.CloudEvents;
import io.github.ilyaslabs.foodstack.platform.mongo.InboxGuard;
import io.github.ilyaslabs.foodstack.rider.core.application.RiderActivity;
import io.github.ilyaslabs.foodstack.rider.core.persistence.AssignmentDocument;
import io.github.ilyaslabs.foodstack.rider.core.persistence.EarningDocument;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.kafka.annotation.KafkaListener;

/**
 * Keeps riders' deliveries in step with dispatch (TDD 5.6): offers count towards the acceptance
 * rate, assignments decide who may follow the rider and keep a busy rider out of matching, and a
 * completed delivery records the rider's earning. Each event is applied once (inbox).
 */
public class DispatchEventsListener {

    static final String CONSUMER = "rider-dispatch";

    private static final String OFFERED = EventTypes.type("dispatch", RiderOffered.class);

    private static final String ASSIGNED = EventTypes.type("dispatch", RiderAssigned.class);

    private static final String UNASSIGNED = EventTypes.type("dispatch", RiderUnassigned.class);

    private static final String PICKED_UP = EventTypes.type("dispatch", DeliveryPickedUp.class);

    private static final String COMPLETED = EventTypes.type("dispatch", DeliveryCompleted.class);

    private static final String CANCELLED = EventTypes.type("dispatch", DeliveryCancelled.class);

    private static final String HANDED_BACK = EventTypes.type("dispatch", DeliveryHandedBack.class);

    private final RiderActivity activity;

    private final InboxGuard inbox;

    private final JsonMapper jsonMapper;

    private final Clock clock;

    public DispatchEventsListener(RiderActivity activity, InboxGuard inbox, JsonMapper jsonMapper, Clock clock) {
        this.activity = activity;
        this.inbox = inbox;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @KafkaListener(id = "${foodstack.namespace:foodstack}-rider-dispatch", groupId = "${foodstack.namespace:foodstack}-rider",
            topics = "${foodstack.namespace:foodstack}.dispatch.events", properties = "auto.offset.reset=earliest")
    public void onEvent(String value) {
        CloudEvents.Envelope event = CloudEvents.parse(value, this.jsonMapper);
        String type = event.type();
        if (OFFERED.equals(type)) {
            RiderOffered e = event.dataAs(RiderOffered.class, this.jsonMapper);
            this.inbox.processOnce(event.id(), CONSUMER, () -> this.activity.offered(e.riderId()));
        }
        else if (ASSIGNED.equals(type)) {
            RiderAssigned e = event.dataAs(RiderAssigned.class, this.jsonMapper);
            DeliverySummary d = e.delivery();
            boolean acceptedOffer = e.actor() != null && e.actor().startsWith("rider:");
            this.inbox.processOnce(event.id(), CONSUMER, () -> this.activity.assigned(new AssignmentDocument(d.orderId(),
                    e.riderId(), e.riderName(), e.vehicle(), d.customerId(), d.storeId(), d.storeOwnerIds(), d.pickup().lat(),
                    d.pickup().lng(), d.dropoff().lat(), d.dropoff().lng(), false, this.clock.instant()), acceptedOffer));
        }
        else if (PICKED_UP.equals(type)) {
            DeliveryPickedUp e = event.dataAs(DeliveryPickedUp.class, this.jsonMapper);
            this.inbox.processOnce(event.id(), CONSUMER, () -> this.activity.pickedUp(e.delivery().orderId()));
        }
        else if (UNASSIGNED.equals(type)) {
            RiderUnassigned e = event.dataAs(RiderUnassigned.class, this.jsonMapper);
            this.inbox.processOnce(event.id(), CONSUMER, () -> this.activity.released(e.delivery().orderId(), e.riderId()));
        }
        else if (CANCELLED.equals(type)) {
            DeliveryCancelled e = event.dataAs(DeliveryCancelled.class, this.jsonMapper);
            this.inbox.processOnce(event.id(), CONSUMER, () -> this.activity.released(e.delivery().orderId(), e.riderId()));
        }
        else if (HANDED_BACK.equals(type)) {
            DeliveryHandedBack e = event.dataAs(DeliveryHandedBack.class, this.jsonMapper);
            this.inbox.processOnce(event.id(), CONSUMER, () -> this.activity.released(e.delivery().orderId(), e.riderId()));
        }
        else if (COMPLETED.equals(type)) {
            DeliveryCompleted e = event.dataAs(DeliveryCompleted.class, this.jsonMapper);
            DeliverySummary d = e.delivery();
            if (d.riderId() == null) {
                return;
            }
            this.inbox.processOnce(event.id(), CONSUMER, () -> this.activity.delivered(new EarningDocument(d.orderId(),
                    d.riderId(), d.orderNumber(), d.storeName(), e.earningMinor(), e.tipMinor(), e.cashCollectedMinor(),
                    e.currency(), e.distanceMeters(), event.time() != null ? event.time() : this.clock.instant())));
        }
    }
}
