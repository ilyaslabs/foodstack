package io.github.ilyaslabs.foodstack.store.impl;

import io.github.ilyaslabs.foodstack.store.api.StoreApi;
import io.github.ilyaslabs.foodstack.store.api.StoreIdPage;
import io.github.ilyaslabs.foodstack.store.api.StoreListingView;
import io.github.ilyaslabs.foodstack.store.core.application.StoreListingService;

import io.github.ilyaslabs.foodstack.platform.security.ModuleAccess;

import org.springframework.stereotype.Component;

/**
 * {@link StoreApi} for other modules: delegates to the core listing service. Listings include
 * branches that are not live yet, so only the platform (the search index) and operator staff who
 * manage stores or search may read them (TDD 9.1).
 */
@Component
class StoreApiImpl implements StoreApi {

    private static final String STORE_MANAGE = "store:manage";

    private static final String SEARCH_MANAGE = "search:manage";

    private final StoreListingService listingService;

    StoreApiImpl(StoreListingService listingService) {
        this.listingService = listingService;
    }

    @Override
    public StoreListingView getListing(String storeId) {
        ModuleAccess.requireServiceOr(STORE_MANAGE, SEARCH_MANAGE);
        return this.listingService.listing(storeId);
    }

    @Override
    public StoreIdPage listStoreIds(String afterId, int limit) {
        ModuleAccess.requireServiceOr(STORE_MANAGE, SEARCH_MANAGE);
        return this.listingService.storeIds(afterId, limit);
    }
}
