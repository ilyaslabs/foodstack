package io.github.ilyaslabs.foodstack.payment.api;

/**
 * A card payment to start. Amounts are minor units of {@code currency}.
 *
 * @param orderId the order; one payment per order
 * @param orderNumber the short order number, shown on the payment page
 * @param customerId who pays
 * @param amountMinor what to charge
 * @param currency ISO 4217 code
 * @param description what the customer sees on the payment page, such as the store name
 * @param returnUrl where the gateway sends the customer after paying
 * @param cancelUrl where the gateway sends the customer when they go back
 */
public record StartCardRequest(String orderId, String orderNumber, String customerId, long amountMinor, String currency,
        String description, String returnUrl, String cancelUrl) {
}
