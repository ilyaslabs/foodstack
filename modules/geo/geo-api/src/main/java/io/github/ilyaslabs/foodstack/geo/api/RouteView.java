package io.github.ilyaslabs.foodstack.geo.api;

/**
 * How far and how long by road (TDD 14.1 routing).
 *
 * @param distanceMeters road distance
 * @param durationSeconds driving time at normal traffic
 * @param estimated {@code true} when no routing provider answered and the straight line times the
 *        road factor at an urban average speed was used instead
 */
public record RouteView(long distanceMeters, long durationSeconds, boolean estimated) {
}
