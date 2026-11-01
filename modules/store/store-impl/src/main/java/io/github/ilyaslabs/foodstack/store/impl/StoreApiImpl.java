package io.github.ilyaslabs.foodstack.store.impl;

import io.github.ilyaslabs.foodstack.platform.security.ModuleAccess;
import io.github.ilyaslabs.foodstack.store.api.OrderValidationRequest;
import io.github.ilyaslabs.foodstack.store.api.OrderValidationView;
import io.github.ilyaslabs.foodstack.store.api.StoreApi;
import io.github.ilyaslabs.foodstack.store.api.StoreIdPage;
import io.github.ilyaslabs.foodstack.store.api.StoreListingView;
import io.github.ilyaslabs.foodstack.store.core.application.OrderValidationService;
import io.github.ilyaslabs.foodstack.store.core.application.StoreListingService;

import org.springframework.stereotype.Component;

/**
 * {@link StoreApi} for other modules: delegates to the core listing service. Listings include
 * branches that are not live yet, so only the platform (the search index) and operator staff who
 * manage stores or search may read them (TDD 9.1). Order validation is for any signed-in caller
 * (the cart prices on behalf of the customer).
 */
@Component
class StoreApiImpl implements StoreApi {

    private static final String STORE_MANAGE = "store:manage";

    private static final String SEARCH_MANAGE = "search:manage";

    private final StoreListingService listingService;

    private final OrderValidationService orderValidation;

    StoreApiImpl(StoreListingService listingService, OrderValidationService orderValidation) {
        this.listingService = listingService;
        this.orderValidation = orderValidation;
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

    @Override
    public OrderValidationView validateOrder(OrderValidationRequest request) {
        ModuleAccess.requireAuthenticated();
        return this.orderValidation.validate(request);
    }
}
