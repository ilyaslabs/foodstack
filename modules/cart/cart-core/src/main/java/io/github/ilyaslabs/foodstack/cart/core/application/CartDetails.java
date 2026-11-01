package io.github.ilyaslabs.foodstack.cart.core.application;

import java.util.List;

import io.github.ilyaslabs.foodstack.cart.api.CheckoutView;
import io.github.ilyaslabs.foodstack.store.api.OrderValidationView;

/**
 * A cart priced at the branch's current prices, without delivery, fees or coupon.
 *
 * @param customerId whose cart
 * @param version cart version
 * @param store the branch, or {@code null} while empty
 * @param currency ISO 4217 code, or {@code null} while empty
 * @param lines priced lines
 * @param subtotalMinor food subtotal
 * @param itemCount number of items (quantities added up)
 * @param problems line problems, as {@link CheckoutView#LINE_PROBLEM}
 */
public record CartDetails(String customerId, long version, OrderValidationView.StoreSnapshot store, String currency,
        List<CheckoutView.Line> lines, long subtotalMinor, int itemCount, List<CheckoutView.Problem> problems) {
}
