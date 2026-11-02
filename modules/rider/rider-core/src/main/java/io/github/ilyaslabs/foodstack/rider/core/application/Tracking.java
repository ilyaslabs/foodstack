package io.github.ilyaslabs.foodstack.rider.core.application;

/**
 * Where the rider of one delivery is, for the customer and the store (PRD CU-9).
 *
 * @param orderId order
 * @param riderName the rider's name
 * @param vehicle the rider's vehicle
 * @param pickedUp whether the rider has the food
 * @param lat rider latitude, or {@code null} before the first ping
 * @param lng rider longitude, or {@code null}
 * @param at when the position was taken, epoch seconds, or {@code null}
 * @param pickupLat store latitude
 * @param pickupLng store longitude
 * @param dropoffLat customer latitude
 * @param dropoffLng customer longitude
 * @param etaSeconds how long until the customer has it, or {@code null} without a position
 * @param remainingMeters road distance left, or {@code null}
 */
public record Tracking(String orderId, String riderName, String vehicle, boolean pickedUp, Double lat, Double lng, Long at,
        double pickupLat, double pickupLng, double dropoffLat, double dropoffLng, Long etaSeconds, Long remainingMeters) {
}
