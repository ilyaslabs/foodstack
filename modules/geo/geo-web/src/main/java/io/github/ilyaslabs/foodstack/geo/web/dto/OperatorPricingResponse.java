package io.github.ilyaslabs.foodstack.geo.web.dto;

import java.util.List;

/**
 * The operator's delivery pricing.
 *
 * @param enabled whether operator riders deliver
 * @param baseChargeMinor charge for any delivery
 * @param perKmMinor added per km of road distance
 * @param maxDistanceMeters farthest delivery
 * @param minOrderMinor smallest basket subtotal, 0 for none
 * @param freeDeliveryAboveMinor subtotal from which delivery is free, or {@code null}
 * @param peakMultiplierPercent peak charge as a percentage of the normal one
 * @param peakWindows when peak hours apply
 * @param version current version (also the ETag)
 * @param updatedAt UTC epoch seconds, or {@code null}
 */
public record OperatorPricingResponse(boolean enabled, long baseChargeMinor, long perKmMinor, int maxDistanceMeters,
        long minOrderMinor, Long freeDeliveryAboveMinor, int peakMultiplierPercent, List<PeakWindowDto> peakWindows,
        long version, Long updatedAt) {
}
