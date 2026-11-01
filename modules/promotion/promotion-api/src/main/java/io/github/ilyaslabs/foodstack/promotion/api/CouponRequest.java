package io.github.ilyaslabs.foodstack.promotion.api;

import io.github.ilyaslabs.foodstack.platform.core.Fulfilment;
import io.github.ilyaslabs.foodstack.platform.core.PaymentMethod;

/**
 * An order to check a coupon against. Amounts are minor units of {@code currency}.
 *
 * @param code the code the customer typed, any case
 * @param customerId who orders
 * @param storeId the branch
 * @param brandId its brand
 * @param city the branch's city
 * @param subtotalMinor food subtotal
 * @param deliveryChargeMinor delivery charge before any discount, 0 for pickup
 * @param fulfilment delivery or pickup
 * @param paymentMethod chosen payment method, or {@code null} while not chosen
 * @param operatorMerchant whether the payment is collected on the operator's merchant account (always in M2)
 * @param currency ISO 4217 code
 */
public record CouponRequest(String code, String customerId, String storeId, String brandId, String city, long subtotalMinor,
        long deliveryChargeMinor, Fulfilment fulfilment, PaymentMethod paymentMethod, boolean operatorMerchant,
        String currency) {
}
