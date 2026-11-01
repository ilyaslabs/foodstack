package io.github.ilyaslabs.foodstack.order.api.events;

import io.github.ilyaslabs.foodstack.order.api.OrderSummary;

/**
 * An order was saved, before payment. Type {@code foodstack.order.OrderPlaced.v1}.
 *
 * @param order the order after the change
 * @param actor who made the change
 */
public record OrderPlaced(OrderSummary order, String actor) implements OrderEvent {
}
