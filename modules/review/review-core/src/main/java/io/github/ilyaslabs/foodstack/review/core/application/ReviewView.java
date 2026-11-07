package io.github.ilyaslabs.foodstack.review.core.application;

/**
 * A store review as the caller may see it. Customers see published reviews without the moderation
 * fields; the branch and moderators also see hidden ones, the order number and why.
 *
 * @param id the review
 * @param storeId the branch
 * @param storeName brand and branch name
 * @param rating 1 to 5
 * @param text masked text, or {@code null}
 * @param photoThumbUrl small photo, or {@code null}
 * @param photoUrl large photo, or {@code null}
 * @param customerName first name and initial
 * @param createdAt epoch seconds
 * @param reply the branch's reply, or {@code null}
 * @param status {@code PUBLISHED} or {@code HIDDEN}
 * @param orderNumber for the branch and moderators; {@code null} for others
 * @param flagged in the moderation queue; moderators only
 * @param originalText the text before masking; moderators only
 * @param hiddenReason why it was hidden; the branch and moderators
 */
public record ReviewView(String id, String storeId, String storeName, int rating, String text, String photoThumbUrl,
        String photoUrl, String customerName, long createdAt, ReplyView reply, String status, String orderNumber,
        boolean flagged, String originalText, String hiddenReason) {

    /**
     * @param text the reply
     * @param at epoch seconds
     */
    public record ReplyView(String text, long at) {
    }
}
