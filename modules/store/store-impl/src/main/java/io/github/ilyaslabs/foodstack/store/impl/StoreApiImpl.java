package io.github.ilyaslabs.foodstack.store.impl;

import io.github.ilyaslabs.evl.EVL;
import io.github.ilyaslabs.evl.Flow;
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

    @Flow(value = "store-api-listing", label = "Branch listing for search", description = "Branch, brand, hours, dish names and dietary tags")
    @EVL.Endpoint(method = "API", path = "StoreApi.getListing", caller = "Search module")
    @Override
    public StoreListingView getListing(String storeId) {
        ModuleAccess.requireServiceOr(STORE_MANAGE, SEARCH_MANAGE);
        return this.listingService.listing(storeId);
    }

    @Flow(value = "store-api-store-ids", label = "Page of branch ids", description = "For the search rebuild")
    @EVL.Endpoint(method = "API", path = "StoreApi.listStoreIds", caller = "Search module")
    @Override
    public StoreIdPage listStoreIds(String afterId, int limit) {
        ModuleAccess.requireServiceOr(STORE_MANAGE, SEARCH_MANAGE);
        return this.listingService.storeIds(afterId, limit);
    }

    @Flow(value = "store-api-validate-order", label = "Price and check order lines", description = "Current menu prices, sold-out marks and modifier rules; problems listed, not thrown")
    @EVL.Endpoint(method = "API", path = "StoreApi.validateOrder", caller = "Cart, order")
    @Override
    public OrderValidationView validateOrder(OrderValidationRequest request) {
        ModuleAccess.requireAuthenticated();
        return this.orderValidation.validate(request);
    }
}
