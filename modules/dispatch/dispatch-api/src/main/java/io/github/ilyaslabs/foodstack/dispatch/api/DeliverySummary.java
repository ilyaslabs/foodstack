package io.github.ilyaslabs.foodstack.dispatch.api;

import java.util.Set;

import io.github.ilyaslabs.foodstack.geo.api.DeliveryMode;
import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;

/**
 * What every dispatch event carries, so consumers (order, rider, notification) never call back.
 *
 * @param orderId the order; also the job id (one job per order)
 * @param orderNumber short order number people read out
 * @param customerId who ordered
 * @param storeId the branch
 * @param storeName brand and branch name
 * @param storeOwnerIds people who run the branch
 * @param mode operator or self delivery
 * @param status job status after the event
 * @param riderId the rider on the job, or {@code null}
 * @param riderName the rider's name, or {@code null}
 * @param pickup the store
 * @param dropoff the customer
 * @param language the customer's language, for messages
 */
public record DeliverySummary(String orderId, String orderNumber, String customerId, String storeId, String storeName,
        Set<String> storeOwnerIds, DeliveryMode mode, DeliveryStatus status, String riderId, String riderName, GeoPoint pickup,
        GeoPoint dropoff, String language) {
}
