package io.github.ilyaslabs.foodstack.order.api.events;

import io.github.ilyaslabs.foodstack.order.api.OrderSummary;

/**
 * The store accepted the order.
 *
 * @param order the order after the change
 * @param actor who made the change
 * @param prepMinutes the store's estimate
 * @param readyBy when the store expects the food ready, epoch seconds
 */
public record OrderAccepted(OrderSummary order, String actor, int prepMinutes, long readyBy) implements OrderEvent {
}
