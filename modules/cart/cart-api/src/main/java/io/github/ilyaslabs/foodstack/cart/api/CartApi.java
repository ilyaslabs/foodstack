package io.github.ilyaslabs.foodstack.cart.api;

import io.github.ilyaslabs.moduleswitch.Param;

/**
 * What other modules may ask the cart module. The order module prices the customer's cart at
 * placement with {@link #checkout}, the same calculation the checkout screen shows, then empties the
 * cart with {@link #clear} (TDD 5.3).
 */
public interface CartApi {

    /**
     * Prices the customer's cart for checkout, re-reading prices, availability, the delivery charge
     * and the coupon (PRD CO-2, CO-3, CO-4, CO-6). Problems are listed, not thrown.
     */
    CheckoutView checkout(@Param("request") CheckoutRequest request);

    /**
     * Empties the cart, but only while it is still at {@code cartVersion}, so items added after the
     * order was priced are kept.
     */
    void clear(@Param("customerId") String customerId, @Param("cartVersion") long cartVersion);
}
