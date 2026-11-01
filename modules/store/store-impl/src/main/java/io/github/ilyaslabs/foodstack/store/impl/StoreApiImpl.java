package io.github.ilyaslabs.foodstack.store.impl;

import io.github.ilyaslabs.foodstack.store.api.StoreApi;
import io.github.ilyaslabs.foodstack.store.api.StoreIdPage;
import io.github.ilyaslabs.foodstack.store.api.StoreListingView;
import io.github.ilyaslabs.foodstack.store.core.application.StoreListingService;

import org.springframework.stereotype.Component;

/** {@link StoreApi} for other modules: delegates to the core listing service. */
@Component
class StoreApiImpl implements StoreApi {

    private final StoreListingService listingService;

    StoreApiImpl(StoreListingService listingService) {
        this.listingService = listingService;
    }

    @Override
    public StoreListingView getListing(String storeId) {
        return this.listingService.listing(storeId);
    }

    @Override
    public StoreIdPage listStoreIds(String afterId, int limit) {
        return this.listingService.storeIds(afterId, limit);
    }
}
