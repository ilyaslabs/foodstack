package io.github.ilyaslabs.foodstack.order.api.events;

import io.github.ilyaslabs.foodstack.order.api.OrderSummary;

/**
 * The order is ready for the rider or the customer.
 *
 * @param order the order after the change
 * @param actor who made the change
 */
public record OrderReady(OrderSummary order, String actor) implements OrderEvent {
}
