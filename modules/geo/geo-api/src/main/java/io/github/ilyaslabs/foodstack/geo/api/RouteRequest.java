package io.github.ilyaslabs.foodstack.geo.api;

/**
 * A trip to measure (see {@link GeoApi#route}).
 *
 * @param from start
 * @param to end
 */
public record RouteRequest(GeoPoint from, GeoPoint to) {
}
