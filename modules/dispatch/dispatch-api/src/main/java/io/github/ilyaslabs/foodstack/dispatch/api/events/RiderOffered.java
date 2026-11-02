package io.github.ilyaslabs.foodstack.dispatch.api.events;

import io.github.ilyaslabs.foodstack.dispatch.api.DeliverySummary;
import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;

/**
 * An operator rider was offered the job (PRD DR-3, DR-4); a push reaches the rider's phone.
 *
 * @param delivery the job after the change
 * @param riderId the rider offered
 * @param expiresAt when the offer moves on, epoch seconds
 * @param earningMinor what the rider earns, tips excluded
 * @param currency ISO 4217 code
 */
public record RiderOffered(DeliverySummary delivery, String riderId, long expiresAt, long earningMinor, String currency)
        implements ModuleEvent {

    @Override
    public String subject() {
        return this.delivery.orderId();
    }
}
