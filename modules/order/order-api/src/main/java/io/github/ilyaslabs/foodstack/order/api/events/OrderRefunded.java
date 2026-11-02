package io.github.ilyaslabs.foodstack.order.api.events;

import io.github.ilyaslabs.foodstack.order.api.OrderSummary;
import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;

/**
 * Support refunded part or all of a card order without cancelling it (PRD OF-6).
 *
 * @param order the order after the change
 * @param actor who made the change
 * @param amountMinor amount to give back
 * @param reason why
 */
public record OrderRefunded(OrderSummary order, String actor, long amountMinor, String reason) implements ModuleEvent {

    /** The order id, so all events of an order share a partition. */
    @Override
    public String subject() {
        return this.order.orderId();
    }
}
