package io.github.ilyaslabs.foodstack.geo.core.application;

import java.util.Map;

import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;

/**
 * A new or changed city.
 *
 * @param name name by language; the default language is required
 * @param centre middle of the city
 * @param active whether new branches and riders can be put in it
 * @param pricing the city's own pricing, or {@code null} to use the operator's
 */
public record CityInput(Map<String, String> name, GeoPoint centre, boolean active, CityPricing pricing) {
}
