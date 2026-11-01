package io.github.ilyaslabs.foodstack.geo.web.dto;

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
 */
public record OperatorPricingRequest(boolean enabled, @PositiveOrZero long baseChargeMinor, @PositiveOrZero long perKmMinor,
        @Min(1000) @Max(50_000) int maxDistanceMeters) {
}
