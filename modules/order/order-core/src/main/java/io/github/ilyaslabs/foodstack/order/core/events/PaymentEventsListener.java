package io.github.ilyaslabs.foodstack.order.core.events;

import io.github.ilyaslabs.foodstack.order.core.application.OrderLifecycleService;
import io.github.ilyaslabs.foodstack.order.core.application.OrderPlacementService;
import io.github.ilyaslabs.foodstack.order.core.persistence.OrderDocument;
import io.github.ilyaslabs.foodstack.payment.api.events.PaymentFailed;
import io.github.ilyaslabs.foodstack.payment.api.events.PaymentSucceeded;
import io.github.ilyaslabs.foodstack.payment.api.events.RefundFailed;
import io.github.ilyaslabs.foodstack.payment.api.events.RefundSucceeded;
import io.github.ilyaslabs.foodstack.platform.core.EventTypes;
import io.github.ilyaslabs.foodstack.platform.kafka.CloudEvents;
import io.github.ilyaslabs.foodstack.platform.mongo.InboxGuard;
import io.github.ilyaslabs.foodstack.platform.security.ServiceCalls;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.kafka.annotation.KafkaListener;

/**
 * Moves orders on from payment events (TDD 5.3): paid orders are confirmed for the store and their
 * cart emptied, failed or expired payments fail the order, refund outcomes go on the timeline. Each
 * event is applied once (inbox).
 */
public class PaymentEventsListener {

    static final String CONSUMER = "order-payments";

    private static final String SUCCEEDED = EventTypes.type("payment", PaymentSucceeded.class);

    private static final String FAILED = EventTypes.type("payment", PaymentFailed.class);

    private static final String REFUNDED = EventTypes.type("payment", RefundSucceeded.class);

    private static final String REFUND_FAILED = EventTypes.type("payment", RefundFailed.class);

    private final OrderLifecycleService lifecycle;

    private final OrderPlacementService placement;

    private final InboxGuard inbox;

    private final ServiceCalls serviceCalls;

    private final JsonMapper jsonMapper;

    public PaymentEventsListener(OrderLifecycleService lifecycle, OrderPlacementService placement, InboxGuard inbox,
            ServiceCalls serviceCalls, JsonMapper jsonMapper) {
        this.lifecycle = lifecycle;
        this.placement = placement;
        this.inbox = inbox;
        this.serviceCalls = serviceCalls;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(id = "${foodstack.namespace:foodstack}-order-payments", groupId = "${foodstack.namespace:foodstack}-order",
            topics = "${foodstack.namespace:foodstack}.payment.events", properties = "auto.offset.reset=earliest")
    public void onEvent(String value) {
        CloudEvents.Envelope event = CloudEvents.parse(value, this.jsonMapper);
        String type = event.type();
        if (SUCCEEDED.equals(type)) {
            PaymentSucceeded paid = event.dataAs(PaymentSucceeded.class, this.jsonMapper);
            this.inbox.processOnce(event.id(), CONSUMER, () -> {
                OrderDocument confirmed = this.lifecycle.paymentSucceeded(paid.orderId(), paid.amountMinor());
                if (confirmed != null) {
                    this.serviceCalls.run(() -> this.placement.clearCart(confirmed.customerId(), confirmed.cartVersion()));
                }
            });
        }
        else if (FAILED.equals(type)) {
            PaymentFailed failed = event.dataAs(PaymentFailed.class, this.jsonMapper);
            this.inbox.processOnce(event.id(), CONSUMER, () -> this.lifecycle.paymentFailed(failed.orderId(), failed.reason()));
        }
        else if (REFUNDED.equals(type)) {
            RefundSucceeded refund = event.dataAs(RefundSucceeded.class, this.jsonMapper);
            this.inbox.processOnce(event.id(), CONSUMER,
                    () -> this.lifecycle.refundSucceeded(refund.orderId(), refund.amountMinor()));
        }
        else if (REFUND_FAILED.equals(type)) {
            RefundFailed refund = event.dataAs(RefundFailed.class, this.jsonMapper);
            this.inbox.processOnce(event.id(), CONSUMER,
                    () -> this.lifecycle.refundFailed(refund.orderId(), refund.amountMinor(), refund.reason()));
        }
    }
}
