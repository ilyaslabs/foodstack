package io.github.ilyaslabs.foodstack.cart.web.dto;

import io.github.ilyaslabs.foodstack.platform.core.Fulfilment;
import io.github.ilyaslabs.foodstack.platform.core.PaymentMethod;

/**
 * {@code POST /api/v1/carts/me/checkout}: price the cart as the customer wants it.
 *
 * @param fulfilment {@code DELIVERY} (default) or {@code PICKUP}
 * @param lat delivery latitude, required for delivery
 * @param lng delivery longitude, required for delivery
 * @param couponCode a coupon code, or {@code null}
 * @param tipMinor tip in minor units, 0 for none
 * @param paymentMethod {@code CASH} or {@code CARD}, or {@code null} while not chosen
 */
public record CheckoutQuoteRequest(Fulfilment fulfilment, Double lat, Double lng, String couponCode, Long tipMinor,
        PaymentMethod paymentMethod) {
}
