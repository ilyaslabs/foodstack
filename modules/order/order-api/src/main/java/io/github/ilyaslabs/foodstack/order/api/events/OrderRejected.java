package io.github.ilyaslabs.foodstack.order.api.events;

import io.github.ilyaslabs.foodstack.order.api.OrderSummary;

/**
 * The store rejected the order, or did not accept in time ({@code automatic}). Paid orders are refunded.
 *
 * @param order the order after the change
 * @param actor who made the change
 * @param reason the store's reason
 * @param automatic rejected because the accept time ran out
 * @param refundMinor amount to give back to the card, 0 for cash
 */
public record OrderRejected(OrderSummary order, String actor, String reason, boolean automatic, long refundMinor) implements OrderEvent {
}
