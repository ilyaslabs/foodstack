package io.github.ilyaslabs.foodstack.payment.api;

/**
 * A started card payment.
 *
 * @param orderId the order
 * @param redirectUrl the gateway's hosted payment page
 * @param expiresAt when the page stops accepting payment, epoch seconds
 */
public record CardSessionView(String orderId, String redirectUrl, long expiresAt) {
}
