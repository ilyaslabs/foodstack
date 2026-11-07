package io.github.ilyaslabs.foodstack.review.core.application;

import java.util.List;

/**
 * A customer rates a finished order: the branch, the rider, or both in one go (PRD RV-1).
 *
 * @param orderId the order
 * @param storeRating 1 to 5, or {@code null} to rate only the rider
 * @param text optional, up to {@value ReviewService#MAX_TEXT} characters
 * @param photoMediaId an uploaded {@code REVIEW} photo, or {@code null}
 * @param riderRating 1 to 5, or {@code null}
 * @param riderTags names of {@link RiderTag}
 */
public record SubmitReview(String orderId, Integer storeRating, String text, String photoMediaId, Integer riderRating,
        List<String> riderTags) {
}
