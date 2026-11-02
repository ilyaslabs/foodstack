package io.github.ilyaslabs.foodstack.dispatch.api.events;

import io.github.ilyaslabs.foodstack.dispatch.api.DeliverySummary;
import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;

/**
 * A rider has the job: an operator rider accepted an offer, or a store or support assigned one.
 *
 * @param delivery the job after the change
 * @param riderId the rider
 * @param riderName the rider's name, shown to the customer
 * @param vehicle how the rider travels ({@code MOTORBIKE}, ...)
 * @param actor who made the change
 */
public record RiderAssigned(DeliverySummary delivery, String riderId, String riderName, String vehicle, String actor)
        implements ModuleEvent {

    @Override
    public String subject() {
        return this.delivery.orderId();
    }
}
