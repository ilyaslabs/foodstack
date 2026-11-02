package io.github.ilyaslabs.foodstack.order.core.application;

import io.github.ilyaslabs.foodstack.platform.core.Fulfilment;
import io.github.ilyaslabs.foodstack.platform.core.PaymentMethod;

/**
 * What the customer confirms at checkout.
 *
 * @param fulfilment delivery or pickup
 * @param address where to deliver (delivery orders)
 * @param couponCode coupon, or {@code null}
 * @param tipMinor tip
 * @param paymentMethod cash or card
 * @param expectedTotalMinor the total the customer saw; when the price changed the order is not placed
 * @param contactPhone phone for the store or rider, or {@code null}
 * @param language the customer's language
 */
public record PlaceOrder(Fulfilment fulfilment, Address address, String couponCode, long tipMinor, PaymentMethod paymentMethod,
        Long expectedTotalMinor, String contactPhone, String language) {

    /**
     * @param lat latitude
     * @param lng longitude
     * @param addressLine address as written
     * @param instructions directions, or {@code null}
     */
    public record Address(double lat, double lng, String addressLine, String instructions) {
    }
}
