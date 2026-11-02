package io.github.ilyaslabs.foodstack.dispatch.api.events;

import io.github.ilyaslabs.foodstack.dispatch.api.DeliverySummary;
import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;

/**
 * The rider has the food; the order moves to {@code PICKED_UP}.
 *
 * @param delivery the job after the change
 * @param actor who made the change
 */
public record DeliveryPickedUp(DeliverySummary delivery, String actor) implements ModuleEvent {

    @Override
    public String subject() {
        return this.delivery.orderId();
    }
}
