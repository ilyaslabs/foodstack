package io.github.ilyaslabs.foodstack.order.api.events;

import io.github.ilyaslabs.foodstack.order.api.OrderSummary;
import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;

/**
 * An order changed status. Every event is published on topic {@code foodstack.order.events} with
 * the order id as key, so one order's events stay in order.
 */
public interface OrderEvent extends ModuleEvent {

    /** The order after the change. */
    OrderSummary order();

    /** Who made the change: a user id, {@code system} or {@code payment}. */
    String actor();

    @Override
    default String subject() {
        return order().orderId();
    }
}
