package io.github.ilyaslabs.foodstack.review.core.persistence;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A customer's review of a branch in {@code review_reviews} (shard key {@code { storeId: hashed }},
 * TDD 6.3), one per order (PRD RV-1 to RV-3).
 *
 * @param id time-ordered id, so the newest sort first
 * @param orderId the order it rates
 * @param orderNumber the short order number, for the branch and moderators
 * @param storeId the branch
 * @param brandId its brand, for the brand-wide rating
 * @param storeName brand and branch name
 * @param customerId who wrote it
 * @param customerName first name and initial, as shown publicly
 * @param rating 1 to 5
 * @param text what customers see, blocked words masked; {@code null} for stars only
 * @param originalText the text as written, kept only when words were masked, for moderators
 * @param photo the optional photo
 * @param language the customer's language when ordering
 * @param status {@code PUBLISHED} or {@code HIDDEN}
 * @param flagged waits in the moderation queue
 * @param hiddenReason why an admin hid it
 * @param hiddenBy the admin
 * @param hiddenAt when
 * @param reply the branch's one public reply
 * @param createdAt when it was written
 */
@Document("review_reviews")
public record ReviewDocument(@Id String id, String orderId, String orderNumber, String storeId, String brandId,
        String storeName, String customerId, String customerName, int rating, String text, String originalText, Photo photo,
        String language, String status, boolean flagged, String hiddenReason, String hiddenBy, Instant hiddenAt, Reply reply,
        Instant createdAt) {

    public static final String PUBLISHED = "PUBLISHED";

    public static final String HIDDEN = "HIDDEN";

    /**
     * @param mediaId the media asset, claimed by the review
     * @param thumbUrl small size
     * @param url large size
     */
    public record Photo(String mediaId, String thumbUrl, String url) {
    }

    /**
     * @param text masked like the review
     * @param by who replied
     * @param at when
     */
    public record Reply(String text, String by, Instant at) {
    }
}
