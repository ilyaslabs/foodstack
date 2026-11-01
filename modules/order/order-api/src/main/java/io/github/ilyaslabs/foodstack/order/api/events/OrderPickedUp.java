package io.github.ilyaslabs.foodstack.order.api.events;

import io.github.ilyaslabs.foodstack.order.api.OrderSummary;

/**
 * The order left the store with a rider (delivery orders).
 *
 * @param order the order after the change
 * @param actor who made the change
 */
public record OrderPickedUp(OrderSummary order, String actor) implements OrderEvent {
}
