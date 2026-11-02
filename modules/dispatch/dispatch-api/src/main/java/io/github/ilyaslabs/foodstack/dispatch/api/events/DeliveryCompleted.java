package io.github.ilyaslabs.foodstack.dispatch.api.events;

import io.github.ilyaslabs.foodstack.dispatch.api.DeliverySummary;
import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;

/**
 * The food reached the customer (PRD DR-6); the order moves to {@code DELIVERED} and the rider's
 * earnings are recorded (PRD DR-1c, DR-7).
 *
 * @param delivery the job after the change
 * @param actor who made the change
 * @param riderKind {@code OPERATOR} or {@code STORE}
 * @param deliveryChargeMinor what the customer paid for delivery
 * @param earningMinor the rider's pay for the job, tips excluded (0 for store riders)
 * @param tipMinor the customer's tip, all of it the rider's
 * @param cashCollectedMinor cash the rider took from the customer (cash orders), else 0
 * @param currency ISO 4217 code
 * @param distanceMeters store to customer
 * @param proof {@code CODE}, {@code PHOTO}, {@code CODE_AND_PHOTO} or {@code TAP}
 */
public record DeliveryCompleted(DeliverySummary delivery, String actor, String riderKind, long deliveryChargeMinor,
        long earningMinor, long tipMinor, long cashCollectedMinor, String currency, long distanceMeters, String proof)
        implements ModuleEvent {

    @Override
    public String subject() {
        return this.delivery.orderId();
    }
}
