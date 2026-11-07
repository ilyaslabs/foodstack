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

    /**
     * Removes a branch that is not listed at this version of it (a draft, paused or closed
     * branch). The version orders it with the branch's writes, as for {@link #index}: an older read
     * arriving late is ignored, and removing a branch that was never indexed does not hold back its
     * first listing.
     */
    void delete(String storeId, long version);

    /** Ids of indexed branches of a brand. */
    List<String> storeIdsOfBrand(String brandId);

    /** Ids of indexed branches using a delivery mode. */
    List<String> storeIdsWithDeliveryMode(String deliveryMode);

    /** Matching branches, nearest branch per brand, with cuisine counts; the page starts at {@code page * size}. */
    default Result search(StoreSearchQuery query) {
        return search(query, query.page() * query.size());
    }

    /** As {@link #search(StoreSearchQuery)}, starting at result {@code from}. */
    Result search(StoreSearchQuery query, int from);

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
