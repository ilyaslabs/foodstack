package io.github.ilyaslabs.foodstack.dispatch.api.events;

import io.github.ilyaslabs.foodstack.dispatch.api.DeliverySummary;
import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;

/**
 * The job ended because the order was cancelled or rejected; a rider on it is free again.
 *
 * @param delivery the job after the change
 * @param riderId the rider who was on the job, or {@code null}
 * @param reason why
 */
public record DeliveryCancelled(DeliverySummary delivery, String riderId, String reason) implements ModuleEvent {

    @Override
    public String subject() {
        return this.delivery.orderId();
    }
}
