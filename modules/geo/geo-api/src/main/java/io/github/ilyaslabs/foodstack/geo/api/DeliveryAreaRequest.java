package io.github.ilyaslabs.foodstack.geo.api;

/**
 * The branch whose delivery area is wanted.
 *
 * @param storeId the branch
 * @param storeLocation where the branch is (centre of the operator-delivery circle)
 * @param deliveryMode the branch's current delivery mode
 * @param cityId the branch's city, whose own maximum distance applies when it has its own pricing; {@code null} for none
 */
public record DeliveryAreaRequest(String storeId, GeoPoint storeLocation, DeliveryMode deliveryMode, String cityId) {

    public DeliveryAreaRequest(String storeId, GeoPoint storeLocation, DeliveryMode deliveryMode) {
        this(storeId, storeLocation, deliveryMode, null);
    }
}
