package io.github.ilyaslabs.foodstack.review.core.persistence;

import java.time.Instant;
import java.util.Set;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A finished order the customer may rate, in {@code review_orders} (shard key {@code { _id: hashed }}),
 * built from order and dispatch events, which may arrive in either order. {@code storeReviewId} and
 * {@code riderRated} change only through conditional updates, so each is rated once.
 *
 * @param id the order
 * @param number short order number
 * @param customerId who ordered
 * @param storeId the branch
 * @param brandId its brand
 * @param storeName brand and branch name
 * @param storeOwnerIds who runs the branch
 * @param language the customer's language
 * @param completedAt delivered or collected; {@code null} until the order event arrives
 * @param riderId who delivered, or {@code null} for pickup
 * @param riderName the rider's name
 * @param riderKind {@code OPERATOR} or {@code STORE}
 * @param storeReviewId the review of the branch, once written
 * @param riderRated whether the rider was rated
 */
@Document("review_orders")
public record RateableOrderDocument(@Id String id, String number, String customerId, String storeId, String brandId,
        String storeName, Set<String> storeOwnerIds, String language, Instant completedAt, String riderId, String riderName,
        String riderKind, String storeReviewId, Boolean riderRated) {

    public boolean isRiderRated() {
        return Boolean.TRUE.equals(this.riderRated);
    }
}
