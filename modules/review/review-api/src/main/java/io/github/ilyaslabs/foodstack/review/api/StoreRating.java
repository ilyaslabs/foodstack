package io.github.ilyaslabs.foodstack.review.api;

/**
 * A branch's rating from its published reviews (PRD RV-4).
 *
 * @param storeId the branch
 * @param count published reviews
 * @param average mean of their stars, 1 to 5, rounded to one decimal; 0 when there are none
 */
public record StoreRating(String storeId, long count, double average) {

    public static StoreRating none(String storeId) {
        return new StoreRating(storeId, 0, 0);
    }
}
