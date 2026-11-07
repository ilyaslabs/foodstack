package io.github.ilyaslabs.foodstack.geo.core.application;

import java.util.List;

/**
 * Operator delivery pricing of one city, used instead of the operator's for branches in it.
 *
 * @param baseChargeMinor charge for any delivery
 * @param perKmMinor added per km of road distance
 * @param maxDistanceMeters farthest delivery
 * @param minOrderMinor smallest basket subtotal, 0 for none
 * @param freeDeliveryAboveMinor subtotal from which delivery is free, or {@code null}
 * @param peakMultiplierPercent peak charge as a percentage of the normal one (100 to 300)
 * @param peakWindows when peak hours apply
 */
public record CityPricing(long baseChargeMinor, long perKmMinor, int maxDistanceMeters, long minOrderMinor,
        Long freeDeliveryAboveMinor, int peakMultiplierPercent, List<PeakWindow> peakWindows) {

    public CityPricing {
        peakWindows = (peakWindows != null) ? List.copyOf(peakWindows) : List.of();
    }
}
