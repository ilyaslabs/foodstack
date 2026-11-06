package io.github.ilyaslabs.foodstack.store.core.application;

import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;

/**
 * Another branch of a brand (PRD BB-1, BB-2).
 *
 * @param branchName the branch's name, such as "DHA"; required, and unique within the brand
 * @param legalName registered business name (a franchisee's own)
 * @param taxId tax id, or {@code null}
 * @param address postal address
 * @param location map position, or {@code null} to set later
 * @param cityId the operator's city, or {@code null} for the nearest one
 * @param prepMinutes average preparation time
 * @param pickupEnabled whether customers may collect
 */
public record NewBranch(String branchName, String legalName, String taxId, Address address, GeoPoint location, String cityId,
        int prepMinutes, boolean pickupEnabled) {
}
