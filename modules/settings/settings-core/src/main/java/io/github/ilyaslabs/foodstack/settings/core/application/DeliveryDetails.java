package io.github.ilyaslabs.foodstack.settings.core.application;

import java.time.Instant;

/**
 * Delivery settings (see {@code DeliveryView} for the meaning of each field).
 *
 * @param riderPayBasisPoints rider share of the operator delivery charge
 * @param riderPayMinMinor least rider pay per delivery
 * @param offerTimeoutSeconds time to accept an offer
 * @param searchRadiusMeters first search radius
 * @param searchLeadMinutes search starts this long before the food is ready
 * @param noRiderAlertSeconds support alert after this long without a rider
 * @param proofCodeAboveMinor orders from this total need a delivery code, or {@code null}
 * @param locationRetentionDays days of rider location history
 * @param version current version
 * @param updatedAt last change, or {@code null}
 */
public record DeliveryDetails(int riderPayBasisPoints, long riderPayMinMinor, int offerTimeoutSeconds, int searchRadiusMeters,
        int searchLeadMinutes, int noRiderAlertSeconds, Long proofCodeAboveMinor, int locationRetentionDays, long version,
        Instant updatedAt) {
}
