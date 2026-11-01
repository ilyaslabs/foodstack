package io.github.ilyaslabs.foodstack.order.api.events;

import io.github.ilyaslabs.foodstack.order.api.OrderSummary;

/**
 * The customer collected the order (pickup orders).
 *
 * @param order the order after the change
 * @param actor who made the change
 */
public record OrderCollected(OrderSummary order, String actor) implements OrderEvent {
}
