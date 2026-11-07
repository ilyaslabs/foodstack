package io.github.ilyaslabs.foodstack.review.core.persistence;

import java.util.Map;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A branch's published reviews added up, in {@code review_store_stats} (shard key {@code { _id: hashed }}),
 * changed with {@code $inc} in the same transaction as the review (PRD RV-4).
 *
 * @param id the branch
 * @param brandId its brand, for the brand-wide rating
 * @param count published reviews
 * @param sum their stars added up
 * @param stars published reviews per star, keyed {@code "1"} to {@code "5"}
 */
@Document("review_store_stats")
public record StoreStatsDocument(@Id String id, String brandId, long count, long sum, Map<String, Long> stars) {
}
