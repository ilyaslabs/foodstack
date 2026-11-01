package io.github.ilyaslabs.foodstack.store.core.application;

import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.geo.api.DeliveryMode;
import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;

/**
 * A restaurant to onboard (PRD ST-1, BB-1): creates its brand and its first branch.
 *
 * @param brandName display name, 1 to 80 characters
 * @param branchName branch name such as "Gulberg"; may be {@code null} for a single restaurant
 * @param legalName registered business name
 * @param taxId tax id, may be {@code null}
 * @param description translated description, may be empty
 * @param cuisines up to 10 cuisine tags
 * @param address postal address
 * @param location map position
 * @param deliveryMode who delivers; {@code SELF} by default
 * @param pickupEnabled whether customers may collect
 * @param prepMinutes average preparation time, 5 to 120
 * @param commission default commission of the brand
 */
public record NewStore(String brandName, String branchName, String legalName, String taxId, Map<String, String> description,
        List<String> cuisines, Address address, GeoPoint location, DeliveryMode deliveryMode, boolean pickupEnabled,
        int prepMinutes, Commission commission) {
}
