package io.github.ilyaslabs.foodstack.order.api.events;

import io.github.ilyaslabs.foodstack.order.api.OrderSummary;
import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;

/**
 * An order was paid (card) or placed with cash, and now waits for the store. Promotion redeems the coupon; notification alerts the store.
 *
 * @param order the order after the change
 * @param actor who made the change
 * @param deliveryCode the 4-digit code the customer gives the rider (PRD DR-6), put in the order
 *        email; {@code null} when the order needs none
 */
public record OrderConfirmed(OrderSummary order, String actor, String deliveryCode) implements ModuleEvent {

    /** The order id, so all events of an order share a partition. */
    @Override
    public String subject() {
        return this.order.orderId();
    }
}
