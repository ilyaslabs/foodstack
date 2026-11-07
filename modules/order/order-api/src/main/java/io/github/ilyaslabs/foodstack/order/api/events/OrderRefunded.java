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
 * @param fundedBy who bears the refund when the order is done: {@link #STORE} (the default) or {@link #OPERATOR};
 *        {@code null} when nothing was earned (the money came back for an order that never went ahead)
 */
public record OrderRefunded(OrderSummary order, String actor, long amountMinor, String reason, String fundedBy)
        implements ModuleEvent {

    /** The store gives the money back out of its share. */
    public static final String STORE = "STORE";

    /** The operator gives the money back. */
    public static final String OPERATOR = "OPERATOR";

    public OrderRefunded(OrderSummary order, String actor, long amountMinor, String reason) {
        this(order, actor, amountMinor, reason, STORE);
    }

    /** The order id, so all events of an order share a partition. */
    @Override
    public String subject() {
        return this.order.orderId();
    }
}
