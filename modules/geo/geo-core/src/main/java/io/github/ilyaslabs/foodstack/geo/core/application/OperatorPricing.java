package io.github.ilyaslabs.foodstack.geo.core.application;

import java.time.Instant;
import java.util.List;

/**
 * The operator's delivery pricing.
 *
 * @param enabled whether operator riders deliver
 * @param baseChargeMinor charge for any delivery
 * @param perKmMinor added per km of road distance
 * @param maxDistanceMeters farthest delivery
 * @param minOrderMinor smallest basket subtotal for operator delivery, 0 for none
 * @param freeDeliveryAboveMinor subtotal from which delivery is free, or {@code null}
 * @param peakMultiplierPercent charge during peak hours as a percentage of the normal one (100 to 300)
 * @param peakWindows when peak hours apply
 * @param version current version
 * @param updatedAt last change, or {@code null}
 */
public record OperatorPricing(boolean enabled, long baseChargeMinor, long perKmMinor, int maxDistanceMeters,
        long minOrderMinor, Long freeDeliveryAboveMinor, int peakMultiplierPercent, List<PeakWindow> peakWindows,
        long version, Instant updatedAt) {

    public OperatorPricing {
        peakWindows = (peakWindows != null) ? List.copyOf(peakWindows) : List.of();
    }
}
