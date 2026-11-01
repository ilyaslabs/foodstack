package io.github.ilyaslabs.foodstack.geo.api;

/**
 * A delivery to price.
 *
 * @param storeId the branch
 * @param storeLocation where the branch is
 * @param deliveryMode the branch's current delivery mode
 * @param destination the customer's address
 */
public record QuoteRequest(String storeId, GeoPoint storeLocation, DeliveryMode deliveryMode, GeoPoint destination) {
}
