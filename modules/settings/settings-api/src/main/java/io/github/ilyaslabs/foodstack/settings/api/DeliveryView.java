package io.github.ilyaslabs.foodstack.settings.api;

/**
 * The operator's delivery rules (PRD DR-1c, DR-3, DR-6, SG-3). Amounts are minor units of the
 * deployment currency; shares are basis points (100 = 1%).
 *
 * @param riderPayBasisPoints share of the operator delivery charge an operator rider earns (default 70%)
 * @param riderPayMinMinor least an operator rider earns per delivery, 0 for none
 * @param offerTimeoutSeconds how long a rider has to accept an offer before it moves on (default 30)
 * @param searchRadiusMeters how far from the store riders are searched first (default 5 km); widened twice by half
 * @param searchLeadMinutes riders are searched this long before the food is ready, so they arrive around then
 * @param noRiderAlertSeconds support is alerted when no rider accepted within this time
 * @param proofCodeAboveMinor orders from this total need the customer's 4-digit code on delivery; {@code null} for never
 * @param locationRetentionDays how long rider location history is kept (PRD privacy, default 30)
 * @param version increases with every change
 */
public record DeliveryView(int riderPayBasisPoints, long riderPayMinMinor, int offerTimeoutSeconds, int searchRadiusMeters,
        int searchLeadMinutes, int noRiderAlertSeconds, Long proofCodeAboveMinor, int locationRetentionDays, long version) {
}
