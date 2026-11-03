package io.github.ilyaslabs.foodstack.geo.core.application;

import java.util.List;

/**
 * New operator delivery pricing.
 *
 * @param enabled whether operator riders deliver
 * @param baseChargeMinor 0 or more
 * @param perKmMinor 0 or more
 * @param maxDistanceMeters 1 to 50 km
 * @param minOrderMinor 0 or more
 * @param freeDeliveryAboveMinor more than 0, or {@code null} for never free
 * @param peakMultiplierPercent 100 to 300
 * @param peakWindows at most 10
 */
public record UpdateOperatorPricing(boolean enabled, long baseChargeMinor, long perKmMinor, int maxDistanceMeters,
        long minOrderMinor, Long freeDeliveryAboveMinor, int peakMultiplierPercent, List<PeakWindow> peakWindows) {

    /** Base and distance only, with no minimum, free threshold or peak hours. */
    public UpdateOperatorPricing(boolean enabled, long baseChargeMinor, long perKmMinor, int maxDistanceMeters) {
        this(enabled, baseChargeMinor, perKmMinor, maxDistanceMeters, 0, null, 100, List.of());
    }

    public UpdateOperatorPricing {
        peakWindows = (peakWindows != null) ? List.copyOf(peakWindows) : List.of();
    }
}
