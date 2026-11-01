package io.github.ilyaslabs.foodstack.store.api;

import io.github.ilyaslabs.moduleswitch.Param;

/**
 * What other modules may ask the store module. In code a branch is a <em>store</em>
 * ({@code storeId}); only the UI says Brand and Branch (TDD 5.7). Errors: {@code STORE_NOT_FOUND} (404).
 */
public interface StoreApi {

    /**
     * Everything discovery needs about a branch, with its brand's fields copied in: the source of
     * the search index, re-read on every store event.
     */
    StoreListingView getListing(@Param("storeId") String storeId);

    /** Branch ids in id order, for a full search rebuild: pass the last id of a page as {@code afterId}. */
    StoreIdPage listStoreIds(@Param("afterId") String afterId, @Param("limit") int limit);
}
