package io.github.ilyaslabs.foodstack.notification.core.events;

import java.util.Map;
import java.util.Set;

import io.github.ilyaslabs.foodstack.notification.core.application.OrderMessages;
import io.github.ilyaslabs.foodstack.order.api.events.OrderAccepted;
import io.github.ilyaslabs.foodstack.order.api.events.OrderCancelled;
import io.github.ilyaslabs.foodstack.order.api.events.OrderConfirmed;
import io.github.ilyaslabs.foodstack.order.api.events.OrderDelivered;
import io.github.ilyaslabs.foodstack.order.api.events.OrderFailed;
import io.github.ilyaslabs.foodstack.order.api.events.OrderPickedUp;
import io.github.ilyaslabs.foodstack.order.api.events.OrderReady;
import io.github.ilyaslabs.foodstack.order.api.events.OrderRejected;
import io.github.ilyaslabs.foodstack.payment.api.events.RefundSucceeded;
import io.github.ilyaslabs.foodstack.platform.core.EventTypes;
import io.github.ilyaslabs.foodstack.platform.kafka.CloudEvents;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.kafka.annotation.KafkaListener;

/**
 * Tells customers and stores about their orders (PRD 4.10, TR-2): order and payment events become
 * messages. Each message is keyed by its event, so redelivery sends nothing twice.
 */
public class OrderNotificationsListener {

    private static final Map<String, Class<?>> ORDER_EVENTS = Map.of(
            EventTypes.type("order", OrderConfirmed.class), OrderConfirmed.class,
            EventTypes.type("order", OrderAccepted.class), OrderAccepted.class,
            EventTypes.type("order", OrderReady.class), OrderReady.class,
            EventTypes.type("order", OrderPickedUp.class), OrderPickedUp.class,
            EventTypes.type("order", OrderDelivered.class), OrderDelivered.class,
            EventTypes.type("order", OrderRejected.class), OrderRejected.class,
            EventTypes.type("order", OrderCancelled.class), OrderCancelled.class,
            EventTypes.type("order", OrderFailed.class), OrderFailed.class);

    private static final Set<String> PAYMENT_EVENTS = Set.of(EventTypes.type("payment", RefundSucceeded.class));

    private final OrderMessages messages;

    private final JsonMapper jsonMapper;

    public OrderNotificationsListener(OrderMessages messages, JsonMapper jsonMapper) {
        this.messages = messages;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(concurrency = "${foodstack.kafka.concurrency.notification:${foodstack.kafka.listener-concurrency:3}}",
            id = "${foodstack.namespace:foodstack}-notification-orders",
            groupId = "${foodstack.namespace:foodstack}-notification",
            topics = { "${foodstack.namespace:foodstack}.order.events", "${foodstack.namespace:foodstack}.payment.events" },
            properties = "auto.offset.reset=earliest")
    public void onEvent(String value) {
        CloudEvents.Envelope event = CloudEvents.parse(value, this.jsonMapper);
        Class<?> type = ORDER_EVENTS.get(event.type());
        if (type != null) {
            this.messages.onOrderEvent(event.id(), event.dataAs(type, this.jsonMapper));
        }
        else if (PAYMENT_EVENTS.contains(event.type())) {
            this.messages.onRefund(event.id(), event.dataAs(RefundSucceeded.class, this.jsonMapper));
        }
    }
}
