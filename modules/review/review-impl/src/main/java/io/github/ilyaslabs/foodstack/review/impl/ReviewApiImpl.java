package io.github.ilyaslabs.foodstack.review.impl;

import io.github.ilyaslabs.foodstack.review.api.ReviewApi;
import io.github.ilyaslabs.foodstack.review.api.StoreRating;
import io.github.ilyaslabs.foodstack.review.core.application.ReviewService;

import org.springframework.stereotype.Component;

/** {@link ReviewApi} for other modules; a branch's rating is public. */
@Component
class ReviewApiImpl implements ReviewApi {

    private final ReviewService reviews;

    ReviewApiImpl(ReviewService reviews) {
        this.reviews = reviews;
    }

    @Override
    public StoreRating storeRating(String storeId) {
        return this.reviews.storeRating(storeId);
    }
}
