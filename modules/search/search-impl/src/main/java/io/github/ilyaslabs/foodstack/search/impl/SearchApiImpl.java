package io.github.ilyaslabs.foodstack.search.impl;

import io.github.ilyaslabs.evl.EVL;
import io.github.ilyaslabs.evl.Flow;
import io.github.ilyaslabs.foodstack.search.api.SearchApi;
import io.github.ilyaslabs.foodstack.search.api.StoreSearchPage;
import io.github.ilyaslabs.foodstack.search.api.StoreSearchQuery;
import io.github.ilyaslabs.foodstack.search.core.application.StoreSearchService;

import org.springframework.stereotype.Component;

/**
 * {@link SearchApi} for other modules: delegates to the core search service. Open to every caller:
 * store search is public (TDD 9.1).
 */
@Component
class SearchApiImpl implements SearchApi {

    private final StoreSearchService searchService;

    SearchApiImpl(StoreSearchService searchService) {
        this.searchService = searchService;
    }

    @Flow(value = "search-api-stores", label = "Search restaurants for other modules", description = "Same search as the customer app")
    @EVL.Endpoint(method = "API", path = "SearchApi.searchStores", caller = "Other modules")
    @Override
    public StoreSearchPage searchStores(StoreSearchQuery query) {
        return this.searchService.search(query);
    }
}
