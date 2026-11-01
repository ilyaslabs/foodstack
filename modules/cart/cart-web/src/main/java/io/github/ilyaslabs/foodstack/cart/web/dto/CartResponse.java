package io.github.ilyaslabs.foodstack.cart.web.dto;

import java.util.List;

import io.github.ilyaslabs.foodstack.cart.api.CheckoutView;
import io.github.ilyaslabs.foodstack.store.api.OrderValidationView;

/**
 * The customer's cart at current prices. Amounts are minor units of {@code currency}.
 *
 * @param version cart version
 * @param store the branch, or {@code null} while empty
 * @param currency ISO 4217 code, or {@code null} while empty
 * @param lines priced lines
 * @param subtotalMinor food subtotal
 * @param itemCount items, quantities added up (for the cart badge)
 * @param problems lines that can no longer be ordered as they are
 */
public record CartResponse(long version, OrderValidationView.StoreSnapshot store, String currency, List<CheckoutView.Line> lines,
        long subtotalMinor, int itemCount, List<CheckoutView.Problem> problems) {
}
