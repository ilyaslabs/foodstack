package io.github.ilyaslabs.foodstack.payment.api;

/**
 * An order's card payment.
 *
 * @param orderId the order
 * @param status {@code PENDING}, {@code SUCCEEDED}, {@code FAILED} or {@code EXPIRED}
 * @param amountMinor amount charged
 * @param refundedMinor amount given back so far
 * @param currency ISO 4217 code
 * @param gateway the gateway used
 * @param redirectUrl the hosted page while pending, otherwise {@code null}
 * @param expiresAt when the page stops accepting payment, epoch seconds
 */
public record PaymentView(String orderId, String status, long amountMinor, long refundedMinor, String currency, String gateway,
        String redirectUrl, long expiresAt) {
}
