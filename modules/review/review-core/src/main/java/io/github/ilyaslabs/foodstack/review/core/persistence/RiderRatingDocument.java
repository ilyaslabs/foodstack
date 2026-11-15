package io.github.ilyaslabs.foodstack.review.core.persistence;

import java.time.Instant;
import java.util.List;

import io.github.ilyaslabs.evl.EVL;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A customer's private rating of a rider, in {@code review_rider_ratings} (shard key
 * {@code { riderId: hashed }}); seen only by the operator (PRD RV-1, RV-4).
 *
 * @param id the order
 * @param riderId the rider
 * @param riderKind {@code OPERATOR} or {@code STORE}
 * @param storeId the branch the order came from
 * @param customerId who rated
 * @param rating 1 to 5
 * @param tags chosen tags
 * @param createdAt when
 */
@EVL.Database(value = "foodstack_review", table = "review_rider_ratings")
@Document("review_rider_ratings")
public record RiderRatingDocument(@Id String id, String riderId, String riderKind, String storeId, String customerId, int rating,
        List<String> tags, Instant createdAt) {
}
