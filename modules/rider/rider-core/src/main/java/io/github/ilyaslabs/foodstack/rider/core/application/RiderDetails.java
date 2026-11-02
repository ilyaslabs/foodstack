package io.github.ilyaslabs.foodstack.rider.core.application;

import java.util.List;

import io.github.ilyaslabs.foodstack.rider.api.RiderKind;
import io.github.ilyaslabs.foodstack.rider.api.RiderStatus;
import io.github.ilyaslabs.foodstack.rider.api.Vehicle;

/**
 * A rider as operator staff, the rider's store and the rider see it. Times are epoch seconds.
 *
 * @param id rider (user) id
 * @param kind operator fleet or a store's own
 * @param storeId the store a store rider rides for, else {@code null}
 * @param name display name
 * @param phone phone
 * @param vehicle vehicle
 * @param plate number plate, or {@code null}
 * @param city where the rider works
 * @param status active or suspended
 * @param online taking jobs now
 * @param lat last latitude, or {@code null}
 * @param lng last longitude, or {@code null}
 * @param lastSeenAt last ping, or {@code null}
 * @param activeOrderIds deliveries the rider has now
 * @param acceptancePercent offers accepted out of offers made
 * @param deliveries deliveries completed
 * @param createdAt created at
 * @param version profile version, sent back with changes
 */
public record RiderDetails(String id, RiderKind kind, String storeId, String name, String phone, Vehicle vehicle, String plate,
        String city, RiderStatus status, boolean online, Double lat, Double lng, Long lastSeenAt, List<String> activeOrderIds,
        int acceptancePercent, long deliveries, long createdAt, long version) {
}
