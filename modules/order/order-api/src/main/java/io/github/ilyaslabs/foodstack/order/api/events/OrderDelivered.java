package io.github.ilyaslabs.foodstack.order.api.events;

import io.github.ilyaslabs.foodstack.order.api.OrderSummary;

/**
 * The order reached the customer (delivery orders).
 *
 * @param order the order after the change
 * @param actor who made the change
 */
public record OrderDelivered(OrderSummary order, String actor) implements OrderEvent {
}
