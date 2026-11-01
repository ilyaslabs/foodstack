package io.github.ilyaslabs.foodstack.geo.api;

/**
 * The branch whose delivery area is wanted.
 *
 * @param storeId the branch
 * @param storeLocation where the branch is (centre of the operator-delivery circle)
 * @param deliveryMode the branch's current delivery mode
 */
public record DeliveryAreaRequest(String storeId, GeoPoint storeLocation, DeliveryMode deliveryMode) {
}
