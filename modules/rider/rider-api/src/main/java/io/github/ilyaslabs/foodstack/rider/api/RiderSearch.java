package io.github.ilyaslabs.foodstack.rider.api;

import java.util.Set;

/**
 * Which online operator riders to look for (TDD 5.6 step 2).
 *
 * @param lat latitude of the store
 * @param lng longitude of the store
 * @param radiusMeters how far from the store
 * @param limit most riders to return, nearest first
 * @param excludeRiderIds riders to leave out (already asked, or busy)
 */
public record RiderSearch(double lat, double lng, int radiusMeters, int limit, Set<String> excludeRiderIds) {
}
