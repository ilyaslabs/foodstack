package io.github.ilyaslabs.foodstack.dispatch.api.events;

import io.github.ilyaslabs.foodstack.dispatch.api.DeliverySummary;
import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;

/**
 * Support gave an operator delivery back to the store (PRD OF-8): the store delivers it itself and
 * the delivery charge goes to the store.
 *
 * @param delivery the job after the change
 * @param riderId the rider who was on the job, or {@code null}
 * @param actor who made the change
 * @param reason why
 */
public record DeliveryHandedBack(DeliverySummary delivery, String riderId, String actor, String reason) implements ModuleEvent {

    @Override
    public String subject() {
        return this.delivery.orderId();
    }
}
