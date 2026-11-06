package io.github.ilyaslabs.foodstack.geo.core.application;

import java.time.Instant;
import java.util.Map;

import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;

/**
 * A city and, when it has one, its own operator delivery pricing.
 *
 * @param id city id
 * @param name name by language
 * @param centre middle of the city
 * @param active whether new branches and riders can be put in it
 * @param pricing the city's own pricing, or {@code null} for the operator's
 * @param version current version
 * @param updatedAt last change
 */
public record City(String id, Map<String, String> name, GeoPoint centre, boolean active, CityPricing pricing, long version,
        Instant updatedAt) {
}
