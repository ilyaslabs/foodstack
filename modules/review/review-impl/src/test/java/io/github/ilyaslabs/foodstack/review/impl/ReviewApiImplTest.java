package io.github.ilyaslabs.foodstack.review.impl;

import io.github.ilyaslabs.foodstack.review.api.StoreRating;
import io.github.ilyaslabs.foodstack.review.core.application.ReviewService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class ReviewApiImplTest {

    @Test
    @DisplayName("a branch's rating comes from the review service, with no sign-in needed")
    void storeRating() {
        ReviewService reviews = mock(ReviewService.class);
        given(reviews.storeRating("s1")).willReturn(new StoreRating("s1", 3, 4.3));

        assertThat(new ReviewApiImpl(reviews).storeRating("s1")).isEqualTo(new StoreRating("s1", 3, 4.3));
    }
}
