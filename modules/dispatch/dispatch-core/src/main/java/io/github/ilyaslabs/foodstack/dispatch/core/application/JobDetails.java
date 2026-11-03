package io.github.ilyaslabs.foodstack.dispatch.core.application;

import io.github.ilyaslabs.foodstack.dispatch.api.DeliveryStatus;
import io.github.ilyaslabs.foodstack.geo.api.DeliveryMode;

/**
 * A delivery job as the rider, the store and support see it (PRD DR-4, DR-5). Times are epoch
 * seconds. The customer's phone shows only to the rider on the job, the store and support; the
 * delivery code never shows (the customer reads it out).
 *
 * @param orderId order
 * @param orderNumber order number
 * @param mode operator or self delivery
 * @param status job status
 * @param storeId branch
 * @param storeName branch name
 * @param pickupLat store latitude
 * @param pickupLng store longitude
 * @param pickupAddress store address
 * @param customerName customer's name
 * @param customerPhone customer's phone, or {@code null}
 * @param dropoffLat customer latitude
 * @param dropoffLng customer longitude
 * @param dropoffAddress customer address
 * @param instructions directions, or {@code null}
 * @param riderId rider, or {@code null}
 * @param riderName rider's name
 * @param offerExpiresAt when the open offer lapses, for the rider holding it
 * @param earningMinor what the rider earns (the offer's amount until delivered)
 * @param tipMinor tip
 * @param cashToCollectMinor cash to take at the door
 * @param currency ISO 4217 code
 * @param needsCode whether the customer's code is needed to finish
 * @param distanceMeters store to customer by road
 * @param readyBy when the food should be ready
 * @param searchingSince when the search started, or {@code null}
 * @param closeReason why it was cancelled or handed back
 * @param revision number of changes
 */
public record JobDetails(String orderId, String orderNumber, DeliveryMode mode, DeliveryStatus status, String storeId,
        String storeName, double pickupLat, double pickupLng, String pickupAddress, String customerName, String customerPhone,
        double dropoffLat, double dropoffLng, String dropoffAddress, String instructions, String riderId, String riderName,
        Long offerExpiresAt, long earningMinor, long tipMinor, long cashToCollectMinor, String currency, boolean needsCode,
        long distanceMeters, Long readyBy, Long searchingSince, String closeReason, long revision) {
}
