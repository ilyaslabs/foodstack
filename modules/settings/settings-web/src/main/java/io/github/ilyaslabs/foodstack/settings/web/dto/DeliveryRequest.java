package io.github.ilyaslabs.foodstack.settings.web.dto;

import io.github.ilyaslabs.evl.EVL;

/**
 * {@code PUT /api/v1/settings/delivery} with {@code If-Match}. Shares are basis points (100 = 1%).
 *
 * @param riderPayBasisPoints rider share of the operator delivery charge, 0 to 10000
 * @param riderPayMinMinor least rider pay per delivery, minor units
 * @param offerTimeoutSeconds time to accept an offer, 10 to 120
 * @param searchRadiusMeters first search radius, 500 to 30000
 * @param searchLeadMinutes search starts this long before the food is ready, 0 to 60
 * @param noRiderAlertSeconds support alert after this long without a rider, 60 to 3600
 * @param proofCodeAboveMinor orders from this total need the customer's code, or {@code null} for never
 * @param locationRetentionDays days of rider location history, 1 to 365
 */
@EVL.Request(id = "settings.delivery-request", name = "Delivery Rules Request")
public record DeliveryRequest(int riderPayBasisPoints, long riderPayMinMinor, int offerTimeoutSeconds, int searchRadiusMeters,
        int searchLeadMinutes, int noRiderAlertSeconds, Long proofCodeAboveMinor, int locationRetentionDays) {
}
