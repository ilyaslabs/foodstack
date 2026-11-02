package io.github.ilyaslabs.foodstack.rider.core.application;

import io.github.ilyaslabs.foodstack.rider.api.RiderKind;
import io.github.ilyaslabs.foodstack.rider.api.Vehicle;

/**
 * An online rider on the operator's live map.
 *
 * @param riderId rider
 * @param name name
 * @param kind operator fleet or a store's own
 * @param storeId store of a store rider
 * @param vehicle vehicle
 * @param busy has a delivery now
 * @param lat latitude, or {@code null} before the first ping
 * @param lng longitude, or {@code null}
 * @param at when the position was taken, epoch seconds, or {@code null}
 */
public record LiveRider(String riderId, String name, RiderKind kind, String storeId, Vehicle vehicle, boolean busy, Double lat,
        Double lng, Long at) {
}
