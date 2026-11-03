package io.github.ilyaslabs.foodstack.notification.core.events;

import io.github.ilyaslabs.foodstack.dispatch.api.events.DispatchAlert;
import io.github.ilyaslabs.foodstack.dispatch.api.events.RiderAssigned;
import io.github.ilyaslabs.foodstack.dispatch.api.events.RiderOffered;
import io.github.ilyaslabs.foodstack.notification.core.application.DispatchMessages;
import io.github.ilyaslabs.foodstack.platform.core.EventTypes;
import io.github.ilyaslabs.foodstack.platform.kafka.CloudEvents;
import io.github.ilyaslabs.foodstack.platform.mongo.InboxGuard;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.kafka.annotation.KafkaListener;

/**
 * Turns dispatch events into messages. Messages for the notification centre are keyed by their
 * event; an offer is push only, so the inbox guard keeps a redelivered offer from ringing twice.
 */
public class DispatchNotificationsListener {

    static final String CONSUMER = "notification-dispatch";

    private static final String OFFERED = EventTypes.type("dispatch", RiderOffered.class);

    private static final String ASSIGNED = EventTypes.type("dispatch", RiderAssigned.class);

    private static final String ALERT = EventTypes.type("dispatch", DispatchAlert.class);

    private final DispatchMessages messages;

    private final InboxGuard inbox;

    private final JsonMapper jsonMapper;

    public DispatchNotificationsListener(DispatchMessages messages, InboxGuard inbox, JsonMapper jsonMapper) {
        this.messages = messages;
        this.inbox = inbox;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(id = "${foodstack.namespace:foodstack}-notification-dispatch",
            groupId = "${foodstack.namespace:foodstack}-notification",
            topics = "${foodstack.namespace:foodstack}.dispatch.events", properties = "auto.offset.reset=earliest")
    public void onEvent(String value) {
        CloudEvents.Envelope event = CloudEvents.parse(value, this.jsonMapper);
        if (OFFERED.equals(event.type())) {
            RiderOffered e = event.dataAs(RiderOffered.class, this.jsonMapper);
            this.inbox.processOnce(event.id(), CONSUMER, () -> this.messages.onOffered(event.id(), e));
        }
        else if (ASSIGNED.equals(event.type())) {
            this.messages.onAssigned(event.id(), event.dataAs(RiderAssigned.class, this.jsonMapper));
        }
        else if (ALERT.equals(event.type())) {
            this.messages.onAlert(event.id(), event.dataAs(DispatchAlert.class, this.jsonMapper));
        }
    }
}
