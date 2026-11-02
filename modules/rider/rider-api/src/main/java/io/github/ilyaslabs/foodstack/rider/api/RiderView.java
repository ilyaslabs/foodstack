package io.github.ilyaslabs.foodstack.rider.api;

/**
 * A rider as other modules see it.
 *
 * @param riderId the rider's user id
 * @param kind operator or store rider
 * @param storeId the store of a store rider, or {@code null}
 * @param name name shown to stores and customers
 * @param vehicle how the rider travels
 * @param status active or suspended
 * @param online whether the rider is online now
 * @param lat last known latitude, or {@code null}
 * @param lng last known longitude, or {@code null}
 * @param lastSeenAt last location report, epoch seconds, or {@code null}
 */
public record RiderView(String riderId, RiderKind kind, String storeId, String name, Vehicle vehicle, RiderStatus status,
        boolean online, Double lat, Double lng, Long lastSeenAt) {
}
