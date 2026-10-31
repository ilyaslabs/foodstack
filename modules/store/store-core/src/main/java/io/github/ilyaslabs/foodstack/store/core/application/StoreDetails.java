package io.github.ilyaslabs.foodstack.store.core.application;

import java.time.DayOfWeek;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.ilyaslabs.foodstack.geo.api.DeliveryMode;
import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import io.github.ilyaslabs.foodstack.store.api.StoreStatus;
import io.github.ilyaslabs.foodstack.store.api.TimeRange;

/**
 * A branch with its brand.
 *
 * @param id branch id
 * @param brand its brand
 * @param branchName branch name, or {@code null}
 * @param legalName registered business name
 * @param taxId tax id
 * @param ownerUserIds owners of the branch
 * @param address postal address
 * @param location map position, or {@code null}
 * @param openingHours ranges per weekday
 * @param closures holiday closures
 * @param temporarilyClosed the owner's safety switch
 * @param closedReason reason shown while closed
 * @param prepMinutes average preparation time
 * @param deliveryMode who delivers
 * @param operatorDeliveryAllowed whether operator delivery may be chosen
 * @param pickupEnabled whether customers may collect
 * @param commission the commission in force (branch override or brand default)
 * @param status visibility
 * @param timezone IANA zone of the hours
 * @param openNow whether it takes orders right now
 * @param version current version
 * @param createdAt onboarding time
 * @param updatedAt last change
 */
public record StoreDetails(String id, BrandDetails brand, String branchName, String legalName, String taxId,
        Set<String> ownerUserIds, Address address, GeoPoint location, Map<DayOfWeek, List<TimeRange>> openingHours,
        List<Closure> closures, boolean temporarilyClosed, String closedReason, int prepMinutes, DeliveryMode deliveryMode,
        boolean operatorDeliveryAllowed, boolean pickupEnabled, Commission commission, StoreStatus status, String timezone,
        boolean openNow, long version, Instant createdAt, Instant updatedAt) {
}
