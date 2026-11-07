package io.github.ilyaslabs.foodstack.review.core.application;

import java.util.List;

/**
 * A branch's rating and its brand's (PRD RV-4, BB-10).
 *
 * @param storeId the branch
 * @param count the branch's published reviews
 * @param average their mean, one decimal; 0 with none
 * @param stars reviews with 1 to 5 stars, in that order
 * @param brandId the brand, or {@code null} before the first review
 * @param brandCount published reviews of every branch of the brand
 * @param brandAverage their mean, one decimal
 */
public record RatingSummary(String storeId, long count, double average, List<Long> stars, String brandId, long brandCount,
        double brandAverage) {
}
