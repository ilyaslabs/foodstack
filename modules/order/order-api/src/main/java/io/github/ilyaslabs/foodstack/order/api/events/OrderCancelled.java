package io.github.ilyaslabs.foodstack.order.api.events;

import io.github.ilyaslabs.foodstack.order.api.OrderSummary;

/**
 * The customer (before acceptance) or support cancelled the order. Paid orders are refunded.
 *
 * @param order the order after the change
 * @param actor who made the change
 * @param reason why
 * @param refundMinor amount to give back to the card, 0 for cash or nothing paid
 */
public record OrderCancelled(OrderSummary order, String actor, String reason, long refundMinor) implements OrderEvent {
}
