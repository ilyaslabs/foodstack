package io.github.ilyaslabs.foodstack.search.api;

import java.util.List;

/**
 * A page of search results: open branches first, then by relevance or distance.
 *
 * @param stores branches on this page
 * @param cuisines cuisines among all matches, most common first (before the cuisine filter)
 * @param total matching brands
 * @param page zero-based page
 * @param size page size
 * @param hasMore whether another page follows (results stop at the configured depth, 500 by default)
 * @param radiusMeters how far this search looked, road distance
 * @param widerRadiusMeters the next, wider step for "show restaurants further away", or {@code null}
 *        at the widest step and for pickup
 */
public record StoreSearchPage(List<StoreCard> stores, List<CuisineCount> cuisines, long total, int page, int size,
        boolean hasMore, int radiusMeters, Integer widerRadiusMeters) {
}
