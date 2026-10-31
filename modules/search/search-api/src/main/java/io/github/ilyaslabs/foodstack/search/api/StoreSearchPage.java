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
 */
public record StoreSearchPage(List<StoreCard> stores, List<CuisineCount> cuisines, long total, int page, int size) {
}
