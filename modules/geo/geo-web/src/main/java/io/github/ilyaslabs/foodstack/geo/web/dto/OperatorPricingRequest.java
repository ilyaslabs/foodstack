package io.github.ilyaslabs.foodstack.geo.web.dto;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * {@code PUT /api/v1/geo/operator-pricing} with {@code If-Match}.
 *
 * @param enabled whether operator riders deliver
 * @param baseChargeMinor charge for any delivery
 * @param perKmMinor added per km of road distance
 * @param maxDistanceMeters farthest delivery
 * @param minOrderMinor smallest basket subtotal, {@code null} or 0 for none
 * @param freeDeliveryAboveMinor subtotal from which delivery is free, {@code null} for never
 * @param peakMultiplierPercent peak charge as a percentage of the normal one, {@code null} for 100
 * @param peakWindows when peak hours apply, in the deployment's time zone
 */
public record OperatorPricingRequest(boolean enabled, @PositiveOrZero long baseChargeMinor, @PositiveOrZero long perKmMinor,
        @Min(1000) @Max(50_000) int maxDistanceMeters, @PositiveOrZero Long minOrderMinor, Long freeDeliveryAboveMinor,
        Integer peakMultiplierPercent, List<PeakWindowDto> peakWindows) {
}
