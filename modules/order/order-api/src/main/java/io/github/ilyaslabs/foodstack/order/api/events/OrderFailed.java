package io.github.ilyaslabs.foodstack.order.api.events;

import io.github.ilyaslabs.foodstack.order.api.OrderSummary;
import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;

/**
 * The card payment failed or the customer left the payment page until it expired.
 *
 * @param order the order after the change
 * @param actor who made the change
 * @param reason the gateway's or the platform's reason
 */
public record OrderFailed(OrderSummary order, String actor, String reason) implements ModuleEvent {

    /** The order id, so all events of an order share a partition. */
    @Override
    public String subject() {
        return this.order.orderId();
    }
}
