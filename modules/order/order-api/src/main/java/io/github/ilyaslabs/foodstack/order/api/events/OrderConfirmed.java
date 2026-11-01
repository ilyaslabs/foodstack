package io.github.ilyaslabs.foodstack.order.api.events;

import io.github.ilyaslabs.foodstack.order.api.OrderSummary;

/**
 * An order was paid (card) or placed with cash, and now waits for the store. Promotion redeems the coupon; notification alerts the store.
 *
 * @param order the order after the change
 * @param actor who made the change
 */
public record OrderConfirmed(OrderSummary order, String actor) implements OrderEvent {
}
