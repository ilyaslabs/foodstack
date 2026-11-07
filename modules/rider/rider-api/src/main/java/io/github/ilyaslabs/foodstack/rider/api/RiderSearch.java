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
 * @param cityId the store's city; riders of another city are left out, riders without a city are not.
 *        {@code null} for any city
 */
public record RiderSearch(double lat, double lng, int radiusMeters, int limit, Set<String> excludeRiderIds, String cityId) {

    /** A search in any city. */
    public RiderSearch(double lat, double lng, int radiusMeters, int limit, Set<String> excludeRiderIds) {
        this(lat, lng, radiusMeters, limit, excludeRiderIds, null);
    }
}
