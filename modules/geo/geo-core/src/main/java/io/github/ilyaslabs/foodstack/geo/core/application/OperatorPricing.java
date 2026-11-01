package io.github.ilyaslabs.foodstack.geo.core.application;

import java.time.Instant;

/**
 * The operator's delivery pricing.
 *
 * @param enabled whether operator riders deliver
 * @param baseChargeMinor charge for any delivery
 * @param perKmMinor added per km of road distance
 * @param maxDistanceMeters farthest delivery
 * @param version current version
 * @param updatedAt last change, or {@code null}
 */
public record OperatorPricing(boolean enabled, long baseChargeMinor, long perKmMinor, int maxDistanceMeters, long version,
        Instant updatedAt) {
}
