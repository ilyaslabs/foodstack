package io.github.ilyaslabs.foodstack.order.core.events;

import io.github.ilyaslabs.evl.EVL;
import io.github.ilyaslabs.evl.Flow;
import io.github.ilyaslabs.foodstack.identity.api.events.UserDeleted;
import io.github.ilyaslabs.foodstack.order.core.application.CustomerErasure;
import io.github.ilyaslabs.foodstack.platform.core.EventTypes;
import io.github.ilyaslabs.foodstack.platform.kafka.CloudEvents;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.kafka.annotation.KafkaListener;

/** Anonymises a deleted customer's orders (PRD ID-7). Erasure is idempotent, so no inbox guard. */
@EVL.Queue("foodstack.identity.events")
public class IdentityEventsListener {

    private static final String DELETED = EventTypes.type("identity", UserDeleted.class);

    private final CustomerErasure erasure;

    private final JsonMapper jsonMapper;

    public IdentityEventsListener(CustomerErasure erasure, JsonMapper jsonMapper) {
        this.erasure = erasure;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(concurrency = "${foodstack.kafka.concurrency.order:${foodstack.kafka.listener-concurrency:3}}",
            id = "${foodstack.namespace:foodstack}-order-identity", groupId = "${foodstack.namespace:foodstack}-order",
            topics = "${foodstack.namespace:foodstack}.identity.events", properties = "auto.offset.reset=earliest")
    @Flow(value = "order-on-user-deleted", label = "Anonymise orders of a deleted account", after = "identity-account-deletion")
    @EVL.Job(trigger = "UserDeleted")
    public void onEvent(String value) {
        CloudEvents.Envelope event = CloudEvents.parse(value, this.jsonMapper);
        if (DELETED.equals(event.type())) {
            this.erasure.erase(event.dataAs(UserDeleted.class, this.jsonMapper).userId());
        }
    }
}
