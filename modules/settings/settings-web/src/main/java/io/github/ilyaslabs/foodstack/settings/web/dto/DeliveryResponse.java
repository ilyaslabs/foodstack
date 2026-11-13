package io.github.ilyaslabs.foodstack.settings.web.dto;

import io.github.ilyaslabs.evl.EVL;

/**
 * The operator's delivery rules (see {@code DeliveryRequest}).
 *
 * @param riderPayBasisPoints rider share of the operator delivery charge
 * @param riderPayMinMinor least rider pay per delivery
 * @param offerTimeoutSeconds time to accept an offer
 * @param searchRadiusMeters first search radius
 * @param searchLeadMinutes search starts this long before the food is ready
 * @param noRiderAlertSeconds support alert after this long without a rider
 * @param proofCodeAboveMinor orders from this total need the customer's code, or {@code null}
 * @param locationRetentionDays days of rider location history
 * @param version for {@code If-Match}
 * @param updatedAt last change, epoch seconds, or {@code null}
 */
@EVL.Response(id = "settings.delivery-response", name = "Delivery Rules Response")
public record DeliveryResponse(int riderPayBasisPoints, long riderPayMinMinor, int offerTimeoutSeconds, int searchRadiusMeters,
        int searchLeadMinutes, int noRiderAlertSeconds, Long proofCodeAboveMinor, int locationRetentionDays, long version,
        Long updatedAt) {
}
