package io.github.ilyaslabs.foodstack.geo.core.routing;

import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;

/** Road distance and driving time between two points (TDD 14.1 provider SPI). */
public interface RoutingProvider {

    /**
     * @return the route, or {@code null} when the provider cannot route between the points
     * @throws RuntimeException when the provider is not reachable
     */
    Route route(GeoPoint from, GeoPoint to);

    /**
     * @param distanceMeters road distance
     * @param durationSeconds driving time
     */
    record Route(long distanceMeters, long durationSeconds) {
    }
}
