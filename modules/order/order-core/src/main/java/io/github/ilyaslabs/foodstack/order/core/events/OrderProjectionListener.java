package io.github.ilyaslabs.foodstack.order.core.events;

import io.github.ilyaslabs.foodstack.order.core.application.OrderProjections;
import io.github.ilyaslabs.foodstack.platform.kafka.CloudEvents;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.kafka.annotation.KafkaListener;

/** Feeds the order lists from the module's own events (TDD 6.3: projections follow the outbox). */
public class OrderProjectionListener {

    private final OrderProjections projections;

    private final JsonMapper jsonMapper;

    public OrderProjectionListener(OrderProjections projections, JsonMapper jsonMapper) {
        this.projections = projections;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(id = "${foodstack.namespace:foodstack}-order-projections",
            groupId = "${foodstack.namespace:foodstack}-order-projections",
            topics = "${foodstack.namespace:foodstack}.order.events", properties = "auto.offset.reset=earliest")
    public void onEvent(String value) {
        CloudEvents.Envelope event = CloudEvents.parse(value, this.jsonMapper);
        if (event.subject() != null) {
            this.projections.refresh(event.subject());
        }
    }
}
