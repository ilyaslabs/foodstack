package io.github.ilyaslabs.foodstack.rider.api;

/**
 * An online, active operator rider near a store, free for a new job.
 *
 * @param riderId the rider
 * @param name the rider's name
 * @param vehicle how the rider travels
 * @param distanceMeters straight-line distance to the store
 * @param idleSeconds how long the rider has waited since going online or finishing the last job
 * @param acceptancePercent offers accepted out of offers made, 0 to 100 (100 for a new rider)
 */
public record RiderCandidate(String riderId, String name, Vehicle vehicle, long distanceMeters, long idleSeconds,
        int acceptancePercent) {
}
