package io.github.ilyaslabs.foodstack.dispatch.api.events;

import io.github.ilyaslabs.foodstack.dispatch.api.DeliverySummary;
import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;

/**
 * A rider was taken off a job before pickup (support reassigned it, or the rider dropped it).
 *
 * @param delivery the job after the change
 * @param riderId the rider taken off
 * @param reason why
 * @param actor who made the change
 */
public record RiderUnassigned(DeliverySummary delivery, String riderId, String reason, String actor) implements ModuleEvent {

    @Override
    public String subject() {
        return this.delivery.orderId();
    }
}
