package io.github.ilyaslabs.foodstack.search.core.engine;

import java.util.List;

import io.github.ilyaslabs.foodstack.search.api.CuisineCount;
import io.github.ilyaslabs.foodstack.search.api.StoreSearchQuery;

/**
 * The search engine behind store discovery (TDD 11). Elasticsearch is the one implementation; the
 * interface keeps it replaceable.
 */
public interface StoreSearchEngine {

    /** Creates the index and its alias when missing; {@code true} when it had to (the caller then rebuilds). */
    boolean ensureIndex();

    void index(IndexedStore store);

    void delete(String storeId);

    /** Ids of indexed branches of a brand. */
    List<String> storeIdsOfBrand(String brandId);

    /** Ids of indexed branches using a delivery mode. */
    List<String> storeIdsWithDeliveryMode(String deliveryMode);

    /** Matching branches, nearest branch per brand, with cuisine counts. */
    Result search(StoreSearchQuery query);

    /**
     * @param hits branches on the page, in engine order
     * @param cuisines cuisines among all matches before the cuisine filter
     * @param total matching brands after every filter
     */
    record Result(List<Hit> hits, List<CuisineCount> cuisines, long total) {
    }

    /**
     * @param store the branch
     * @param distanceMeters distance from the customer
     */
    record Hit(IndexedStore store, long distanceMeters) {
    }
}
