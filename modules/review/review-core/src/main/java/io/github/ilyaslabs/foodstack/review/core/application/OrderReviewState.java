package io.github.ilyaslabs.foodstack.review.core.application;

/**
 * What the customer can still rate on an order, for the order page.
 *
 * @param orderId the order
 * @param canRateStore the branch can be rated now
 * @param canRateRider a rider delivered it and can be rated now
 * @param riderName the rider's name, or {@code null}
 * @param closesAt epoch seconds after which nothing can be rated; {@code null} before the order is finished
 * @param storeReview the customer's review of the branch, once written
 * @param riderRating the stars given to the rider, once rated
 */
public record OrderReviewState(String orderId, boolean canRateStore, boolean canRateRider, String riderName, Long closesAt,
        ReviewView storeReview, Integer riderRating) {
}
