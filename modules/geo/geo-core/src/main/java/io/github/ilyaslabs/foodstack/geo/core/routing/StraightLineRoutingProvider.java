package io.github.ilyaslabs.foodstack.geo.core.routing;

import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import io.github.ilyaslabs.foodstack.geo.core.domain.GeoMath;

/**
 * The estimate used when no routing service is configured, and as the fallback when it does not
 * answer: straight-line distance times the road factor, at an urban average speed.
 */
public class StraightLineRoutingProvider implements RoutingProvider {

    private final double roadFactor;

    private final double speedKmh;

    public StraightLineRoutingProvider(double roadFactor, double speedKmh) {
        this.roadFactor = roadFactor;
        this.speedKmh = speedKmh;
    }

    @Override
    public Route route(GeoPoint from, GeoPoint to) {
        long distance = Math.round(GeoMath.distanceMeters(from, to) * this.roadFactor);
        long seconds = Math.round(distance / (this.speedKmh / 3.6));
        return new Route(distance, seconds);
    }
}
