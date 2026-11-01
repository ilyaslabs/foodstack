package io.github.ilyaslabs.foodstack.geo.web.dto;

/**
 * The operator's delivery pricing.
 *
 * @param enabled whether operator riders deliver
 * @param baseChargeMinor charge for any delivery
 * @param perKmMinor added per km of road distance
 * @param maxDistanceMeters farthest delivery
 * @param version current version (also the ETag)
 * @param updatedAt UTC epoch seconds, or {@code null}
 */
public record OperatorPricingResponse(boolean enabled, long baseChargeMinor, long perKmMinor, int maxDistanceMeters,
        long version, Long updatedAt) {
}
