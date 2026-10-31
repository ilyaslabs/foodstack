package io.github.ilyaslabs.foodstack.store.core.application;

import java.time.Instant;

import io.github.ilyaslabs.foodstack.geo.api.DeliveryMode;
import io.github.ilyaslabs.foodstack.store.api.StoreStatus;

/**
 * A branch in a back-office list.
 *
 * @param id branch id
 * @param brandId brand id
 * @param brandName brand name
 * @param branchName branch name, or {@code null}
 * @param city city
 * @param status visibility
 * @param temporarilyClosed the owner's safety switch
 * @param deliveryMode who delivers
 * @param createdAt onboarding time
 */
public record StoreSummary(String id, String brandId, String brandName, String branchName, String city, StoreStatus status,
        boolean temporarilyClosed, DeliveryMode deliveryMode, Instant createdAt) {
}
