package io.github.ilyaslabs.foodstack.payment.core.events;

import io.github.ilyaslabs.foodstack.order.api.events.OrderCancelled;
import io.github.ilyaslabs.foodstack.order.api.events.OrderRefunded;
import io.github.ilyaslabs.foodstack.order.api.events.OrderRejected;
import io.github.ilyaslabs.foodstack.payment.core.application.PaymentService;
import io.github.ilyaslabs.foodstack.platform.core.EventTypes;
import io.github.ilyaslabs.foodstack.platform.kafka.CloudEvents;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.kafka.annotation.KafkaListener;

/**
 * Refunds what the order module asks for (TDD 5.3): a cancelled or rejected paid order, or a refund
 * by support. The event id is the refund id, so a redelivered event never refunds twice; a gateway
 * that may succeed later makes the handler throw, and the event is retried, then dead-lettered.
 */
public class OrderEventsListener {

    private static final String CANCELLED = EventTypes.type("order", OrderCancelled.class);

    private static final String REJECTED = EventTypes.type("order", OrderRejected.class);

    private static final String REFUNDED = EventTypes.type("order", OrderRefunded.class);

    private final PaymentService payments;

    private final JsonMapper jsonMapper;

    public OrderEventsListener(PaymentService payments, JsonMapper jsonMapper) {
        this.payments = payments;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(id = "${foodstack.namespace:foodstack}-payment-orders", groupId = "${foodstack.namespace:foodstack}-payment",
            topics = "${foodstack.namespace:foodstack}.order.events", properties = "auto.offset.reset=earliest")
    public void onEvent(String value) {
        CloudEvents.Envelope event = CloudEvents.parse(value, this.jsonMapper);
        String type = event.type();
        if (CANCELLED.equals(type)) {
            OrderCancelled cancelled = event.dataAs(OrderCancelled.class, this.jsonMapper);
            this.payments.refund(event.subject(), cancelled.refundMinor(), event.id(), "Order cancelled: " + cancelled.reason());
        }
        else if (REJECTED.equals(type)) {
            OrderRejected rejected = event.dataAs(OrderRejected.class, this.jsonMapper);
            this.payments.refund(event.subject(), rejected.refundMinor(), event.id(), "Order rejected: " + rejected.reason());
        }
        else if (REFUNDED.equals(type)) {
            OrderRefunded refunded = event.dataAs(OrderRefunded.class, this.jsonMapper);
            this.payments.refund(event.subject(), refunded.amountMinor(), event.id(), refunded.reason());
        }
    }
}
