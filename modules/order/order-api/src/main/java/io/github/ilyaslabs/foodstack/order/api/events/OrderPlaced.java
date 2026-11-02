package io.github.ilyaslabs.foodstack.order.api.events;

import io.github.ilyaslabs.foodstack.order.api.OrderSummary;
import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;

/**
 * An order was saved, before payment. Type {@code foodstack.order.OrderPlaced.v1}.
 *
 * @param order the order after the change
 * @param actor who made the change
 */
public record OrderPlaced(OrderSummary order, String actor) implements ModuleEvent {

    /** The order id, so all events of an order share a partition. */
    @Override
    public String subject() {
        return this.order.orderId();
    }
}
