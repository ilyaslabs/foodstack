package io.github.ilyaslabs.foodstack.order.api.events;

import io.github.ilyaslabs.foodstack.order.api.OrderSummary;
import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;

/**
 * The customer (before acceptance) or support cancelled the order. Paid orders are refunded.
 *
 * @param order the order after the change
 * @param actor who made the change
 * @param reason why
 * @param refundMinor amount to give back to the card, 0 for cash or nothing paid
 */
public record OrderCancelled(OrderSummary order, String actor, String reason, long refundMinor) implements ModuleEvent {

    /** The order id, so all events of an order share a partition. */
    @Override
    public String subject() {
        return this.order.orderId();
    }
}
