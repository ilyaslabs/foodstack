package io.github.ilyaslabs.foodstack.review.core.persistence;

import java.util.Map;

import io.github.ilyaslabs.evl.EVL;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A rider's ratings added up, in {@code review_rider_stats} (shard key {@code { _id: hashed }}).
 *
 * @param id the rider
 * @param count ratings
 * @param sum their stars added up
 * @param tags how often each tag was chosen
 */
@EVL.Database(value = "foodstack_review", table = "review_rider_stats")
@Document("review_rider_stats")
public record RiderStatsDocument(@Id String id, long count, long sum, Map<String, Long> tags) {
}
