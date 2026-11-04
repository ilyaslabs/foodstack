package io.github.ilyaslabs.foodstack.store.core.application;

import io.github.ilyaslabs.foodstack.geo.api.DeliveryMode;
import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;

/**
 * Branch details the owner edits.
 *
 * @param branchName branch name, may be {@code null}
 * @param address postal address
 * @param location map position
 * @param prepMinutes average preparation time
 * @param deliveryMode who delivers; operator delivery only when the operator allows it
 * @param pickupEnabled whether customers may collect
 * @param timezone IANA zone of the hours; {@code null} or unchanged with a moved location finds it from the location
 */
public record UpdateBranch(String branchName, Address address, GeoPoint location, int prepMinutes, DeliveryMode deliveryMode,
        boolean pickupEnabled, String timezone) {

    public UpdateBranch(String branchName, Address address, GeoPoint location, int prepMinutes, DeliveryMode deliveryMode,
            boolean pickupEnabled) {
        this(branchName, address, location, prepMinutes, deliveryMode, pickupEnabled, null);
    }
}
