package io.github.ilyaslabs.foodstack.order.api.events;

import io.github.ilyaslabs.foodstack.order.api.OrderDelivery;
import io.github.ilyaslabs.foodstack.order.api.OrderSummary;
import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;

/**
 * The store accepted the order.
 *
 * @param order the order after the change
 * @param actor who made the change
 * @param prepMinutes the store's estimate
 * @param readyBy when the store expects the food ready, epoch seconds
 * @param delivery what dispatch needs (delivery orders), or {@code null} for pickup
 */
public record OrderAccepted(OrderSummary order, String actor, int prepMinutes, long readyBy, OrderDelivery delivery)
        implements ModuleEvent {

    /** The order id, so all events of an order share a partition. */
    @Override
    public String subject() {
        return this.order.orderId();
    }
}
