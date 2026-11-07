package io.github.ilyaslabs.foodstack.dispatch.core.events;

import io.github.ilyaslabs.foodstack.dispatch.core.application.CustomerErasure;
import io.github.ilyaslabs.foodstack.identity.api.events.UserDeleted;
import io.github.ilyaslabs.foodstack.platform.core.EventTypes;
import io.github.ilyaslabs.foodstack.platform.kafka.CloudEvents;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.kafka.annotation.KafkaListener;

/** Anonymises a deleted customer's delivery jobs (PRD ID-7). Erasure is idempotent, so no inbox guard. */
public class IdentityEventsListener {

    private static final String DELETED = EventTypes.type("identity", UserDeleted.class);

    private final CustomerErasure erasure;

    private final JsonMapper jsonMapper;

    public IdentityEventsListener(CustomerErasure erasure, JsonMapper jsonMapper) {
        this.erasure = erasure;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(concurrency = "${foodstack.kafka.concurrency.dispatch:${foodstack.kafka.listener-concurrency:3}}",
            id = "${foodstack.namespace:foodstack}-dispatch-identity", groupId = "${foodstack.namespace:foodstack}-dispatch",
            topics = "${foodstack.namespace:foodstack}.identity.events", properties = "auto.offset.reset=earliest")
    public void onEvent(String value) {
        CloudEvents.Envelope event = CloudEvents.parse(value, this.jsonMapper);
        if (DELETED.equals(event.type())) {
            this.erasure.erase(event.dataAs(UserDeleted.class, this.jsonMapper).userId());
        }
    }
}
