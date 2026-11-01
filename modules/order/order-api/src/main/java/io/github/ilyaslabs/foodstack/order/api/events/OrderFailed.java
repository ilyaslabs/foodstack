package io.github.ilyaslabs.foodstack.order.api.events;

import io.github.ilyaslabs.foodstack.order.api.OrderSummary;

/**
 * The card payment failed or the customer left the payment page until it expired.
 *
 * @param order the order after the change
 * @param actor who made the change
 * @param reason the gateway's or the platform's reason
 */
public record OrderFailed(OrderSummary order, String actor, String reason) implements OrderEvent {
}
