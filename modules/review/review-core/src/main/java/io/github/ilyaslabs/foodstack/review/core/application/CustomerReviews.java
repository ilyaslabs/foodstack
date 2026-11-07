package io.github.ilyaslabs.foodstack.review.core.application;

import java.util.List;

/**
 * A customer's reviews and rider ratings, for their data export (PRD ID-7).
 *
 * @param reviews store reviews, newest first, hidden ones included
 * @param riderRatings rider ratings, newest first
 */
public record CustomerReviews(List<ReviewView> reviews, List<RiderRating> riderRatings) {

    /**
     * @param orderId the rated order
     * @param rating 1 to 5
     * @param tags chosen tags
     * @param createdAt when, epoch seconds
     */
    public record RiderRating(String orderId, int rating, List<String> tags, long createdAt) {
    }
}
