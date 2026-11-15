package io.github.ilyaslabs.foodstack.review.api;

import io.github.ilyaslabs.evl.EVL;
import io.github.ilyaslabs.moduleswitch.Param;

/** What other modules may ask the review module. */
@EVL.Interface(value = "ReviewApi", description = "The review module API: an in-process call, or HTTP when review runs as its own service")
public interface ReviewApi {

    /** A branch's published rating; zero reviews when it has none. Public information. */
    StoreRating storeRating(@Param("storeId") String storeId);
}
