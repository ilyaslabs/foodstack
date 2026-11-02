package io.github.ilyaslabs.foodstack.settings.core.application;

/**
 * New delivery settings.
 *
 * @param riderPayBasisPoints 0 to 10000
 * @param riderPayMinMinor 0 or more
 * @param offerTimeoutSeconds 10 to 120
 * @param searchRadiusMeters 500 to 30000
 * @param searchLeadMinutes 0 to 60
 * @param noRiderAlertSeconds 60 to 3600
 * @param proofCodeAboveMinor 0 or more, or {@code null} for never
 * @param locationRetentionDays 1 to 365
 */
public record UpdateDelivery(int riderPayBasisPoints, long riderPayMinMinor, int offerTimeoutSeconds, int searchRadiusMeters,
        int searchLeadMinutes, int noRiderAlertSeconds, Long proofCodeAboveMinor, int locationRetentionDays) {
}
