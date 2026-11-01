package io.github.ilyaslabs.foodstack.order.api.events;

import io.github.ilyaslabs.foodstack.order.api.OrderSummary;

/**
 * The kitchen started the order.
 *
 * @param order the order after the change
 * @param actor who made the change
 */
public record OrderPreparing(OrderSummary order, String actor) implements OrderEvent {
}
