package io.github.ilyaslabs.foodstack.store.core.application;

/**
 * What delivery from a branch to an address costs, with the time it takes.
 *
 * @param deliverable whether the branch delivers there
 * @param reason why not, or {@code null}
 * @param chargeMinor delivery charge
 * @param minOrderMinor minimum subtotal
 * @param freeDeliveryAboveMinor free-delivery threshold, or {@code null}
 * @param distanceMeters estimated road distance
 * @param etaMinutes preparation plus travel time
 * @param currency ISO 4217 code of the amounts
 */
public record DeliveryQuote(boolean deliverable, String reason, long chargeMinor, long minOrderMinor,
        Long freeDeliveryAboveMinor, long distanceMeters, int etaMinutes, String currency) {
}
