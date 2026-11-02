package io.github.ilyaslabs.foodstack.rider.api;

/**
 * An online, active operator rider near a store.
 *
 * @param riderId the rider
 * @param name the rider's name
 * @param vehicle how the rider travels
 * @param distanceMeters straight-line distance to the store
 * @param onlineSeconds how long the rider has been online, for the idle bonus
 */
public record RiderCandidate(String riderId, String name, Vehicle vehicle, long distanceMeters, long onlineSeconds) {
}
