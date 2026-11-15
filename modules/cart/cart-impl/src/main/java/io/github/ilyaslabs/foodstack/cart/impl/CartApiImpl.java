package io.github.ilyaslabs.foodstack.cart.impl;

import io.github.ilyaslabs.evl.EVL;
import io.github.ilyaslabs.evl.Flow;
import io.github.ilyaslabs.foodstack.cart.api.CartApi;
import io.github.ilyaslabs.foodstack.cart.api.CheckoutRequest;
import io.github.ilyaslabs.foodstack.cart.api.CheckoutView;
import io.github.ilyaslabs.foodstack.cart.core.application.CartService;
import io.github.ilyaslabs.foodstack.cart.core.application.CheckoutService;
import io.github.ilyaslabs.foodstack.platform.security.ModuleAccess;

import org.springframework.stereotype.Component;

/**
 * {@link CartApi} for other modules. A cart is private: only its customer (the order module acts
 * with the customer's token at placement) or the platform may price or empty it (TDD 9.1).
 */
@Component
class CartApiImpl implements CartApi {

    private final CheckoutService checkout;

    private final CartService carts;

    CartApiImpl(CheckoutService checkout, CartService carts) {
        this.checkout = checkout;
        this.carts = carts;
    }

    @Flow(value = "cart-api-checkout", label = "Price cart for an order", description = "Same calculation the checkout screen shows; only the customer or the platform")
    @EVL.Endpoint(method = "API", path = "CartApi.checkout", caller = "Order module")
    @Override
    public CheckoutView checkout(CheckoutRequest request) {
        ModuleAccess.requireServiceSelfOr(request.customerId());
        return this.checkout.checkout(request);
    }

    @Flow(value = "cart-api-clear", label = "Empty cart after an order", description = "Only while it is still at the priced version, so items added later are kept")
    @EVL.Endpoint(method = "API", path = "CartApi.clear", caller = "Order module")
    @Override
    public void clear(String customerId, long cartVersion) {
        ModuleAccess.requireServiceSelfOr(customerId);
        this.carts.clearIfVersion(customerId, cartVersion);
    }
}
