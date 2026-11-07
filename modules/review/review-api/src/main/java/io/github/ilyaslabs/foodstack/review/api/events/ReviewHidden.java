package io.github.ilyaslabs.foodstack.review.api.events;

import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;

/**
 * An admin hid a store review; it no longer counts in the branch's rating (PRD RV-3).
 *
 * @param reviewId the review
 * @param storeId the branch
 * @param brandId the branch's brand
 * @param reason why, shown to the branch
 * @param actor the admin
 * @param count published reviews of the branch after this
 * @param average the branch's new average
 */
public record ReviewHidden(String reviewId, String storeId, String brandId, String reason, String actor, long count,
        double average) implements ModuleEvent {

    /** The branch, so its rating changes stay in order. */
    @Override
    public String subject() {
        return this.storeId;
    }
}
