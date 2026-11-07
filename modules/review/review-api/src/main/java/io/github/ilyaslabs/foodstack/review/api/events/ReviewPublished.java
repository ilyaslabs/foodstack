package io.github.ilyaslabs.foodstack.review.api.events;

import java.util.Set;

import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;

/**
 * A store review now counts in the branch's rating: a new review, or one an admin showed again
 * (PRD RV-1, RV-3).
 *
 * @param reviewId the review
 * @param orderId the order it rates
 * @param storeId the branch
 * @param brandId the branch's brand
 * @param storeName brand and branch name
 * @param storeOwnerIds who runs the branch, for a notice
 * @param rating 1 to 5
 * @param flagged the text had blocked words, now masked, and waits in the moderation queue
 * @param count published reviews of the branch after this one
 * @param average the branch's new average
 */
public record ReviewPublished(String reviewId, String orderId, String storeId, String brandId, String storeName,
        Set<String> storeOwnerIds, int rating, boolean flagged, long count, double average) implements ModuleEvent {

    /** The branch, so its rating changes stay in order. */
    @Override
    public String subject() {
        return this.storeId;
    }
}
