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
 * @param payUrl the app's own page that shows the gateway's embedded fields (used when cards are embedded)
 * @param merchantAccountId the account chosen at placement ({@link PaymentApi#resolveMerchant}), or {@code null}
 *        for the operator's
 */
public record StartCardRequest(String orderId, String orderNumber, String customerId, long amountMinor, String currency,
        String description, String returnUrl, String cancelUrl, String merchantAccountId, String payUrl) {

    /** A payment without an embedded payment page of the app's own. */
    public StartCardRequest(String orderId, String orderNumber, String customerId, long amountMinor, String currency,
            String description, String returnUrl, String cancelUrl, String merchantAccountId) {
        this(orderId, orderNumber, customerId, amountMinor, currency, description, returnUrl, cancelUrl, merchantAccountId,
                null);
    }

    /** A payment into the operator's account. */
    public StartCardRequest(String orderId, String orderNumber, String customerId, long amountMinor, String currency,
            String description, String returnUrl, String cancelUrl) {
        this(orderId, orderNumber, customerId, amountMinor, currency, description, returnUrl, cancelUrl, null);
    }
}
