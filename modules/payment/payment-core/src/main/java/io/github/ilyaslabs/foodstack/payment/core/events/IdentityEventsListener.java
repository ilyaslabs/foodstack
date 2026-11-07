package io.github.ilyaslabs.foodstack.payment.core.events;

import io.github.ilyaslabs.foodstack.identity.api.events.UserDeleted;
import io.github.ilyaslabs.foodstack.payment.core.application.PaymentService;
import io.github.ilyaslabs.foodstack.platform.core.EventTypes;
import io.github.ilyaslabs.foodstack.platform.kafka.CloudEvents;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.kafka.annotation.KafkaListener;

/**
 * Forgets a deleted customer's saved cards at the gateway (PRD ID-7). Erasure is safe to repeat, so
 * no inbox guard; a gateway error is thrown, so the event is tried again.
 */
public class IdentityEventsListener {

    private static final String DELETED = EventTypes.type("identity", UserDeleted.class);

    private final PaymentService payments;

    private final JsonMapper jsonMapper;

    public IdentityEventsListener(PaymentService payments, JsonMapper jsonMapper) {
        this.payments = payments;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(concurrency = "${foodstack.kafka.concurrency.payment:${foodstack.kafka.listener-concurrency:3}}",
            id = "${foodstack.namespace:foodstack}-payment-identity", groupId = "${foodstack.namespace:foodstack}-payment",
            topics = "${foodstack.namespace:foodstack}.identity.events", properties = "auto.offset.reset=earliest")
    public void onEvent(String value) {
        CloudEvents.Envelope event = CloudEvents.parse(value, this.jsonMapper);
        if (DELETED.equals(event.type())) {
            this.payments.eraseCustomer(event.dataAs(UserDeleted.class, this.jsonMapper).userId());
        }
    }
}
