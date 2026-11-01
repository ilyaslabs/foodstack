package io.github.ilyaslabs.foodstack.cart.api;

import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import io.github.ilyaslabs.foodstack.platform.core.Fulfilment;
import io.github.ilyaslabs.foodstack.platform.core.PaymentMethod;

/**
 * How the customer wants the cart.
 *
 * @param customerId whose cart
 * @param fulfilment delivery or pickup
 * @param destination where to deliver; required for delivery, ignored for pickup
 * @param couponCode a coupon code, or {@code null}
 * @param tipMinor tip for the rider in minor units, 0 for none
 * @param paymentMethod chosen method, or {@code null} while not chosen (some coupons depend on it)
 */
public record CheckoutRequest(String customerId, Fulfilment fulfilment, GeoPoint destination, String couponCode, long tipMinor,
        PaymentMethod paymentMethod) {
}
