package io.github.ilyaslabs.foodstack.geo.core.application;

/**
 * New operator delivery pricing.
 *
 * @param enabled whether operator riders deliver
 * @param baseChargeMinor 0 or more
 * @param perKmMinor 0 or more
 * @param maxDistanceMeters 1 to 50 km
 */
public record UpdateOperatorPricing(boolean enabled, long baseChargeMinor, long perKmMinor, int maxDistanceMeters) {
}
