package io.github.ilyaslabs.foodstack.review.api;

import io.github.ilyaslabs.moduleswitch.Param;

/** What other modules may ask the review module. */
public interface ReviewApi {

    /** A branch's published rating; zero reviews when it has none. Public information. */
    StoreRating storeRating(@Param("storeId") String storeId);
}
