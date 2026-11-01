package io.github.ilyaslabs.foodstack.payment.core.gateway;

import java.time.Instant;
import java.util.Map;

/**
 * A card gateway in the hosted-page style (TDD 5.4): the customer pays on the gateway's own page,
 * so card data never touches FoodStack. Adapters exist for a local sandbox and for Stripe Checkout;
 * others implement this interface.
 */
public interface CardGateway {

    /** The id used in configuration and in the webhook URL, such as {@code stripe}. */
    String id();

    /** Opens a hosted payment page for an order. */
    HostedSession createSession(SessionRequest request);

    /**
     * Verifies a webhook's signature and reads it. Returns {@code null} for events the platform does
     * not act on. Throws {@link GatewayException} when the signature is wrong.
     */
    WebhookEvent parseWebhook(byte[] body, Map<String, String> headers);

    /** Gives money back. {@code idempotencyKey} makes a retried call safe. */
    String refund(String paymentReference, long amountMinor, String currency, String idempotencyKey);

    /**
     * A page to open.
     *
     * @param orderId the order, sent to the gateway so its webhooks name the order
     * @param orderNumber short order number
     * @param amountMinor amount in minor units
     * @param currency ISO 4217 code
     * @param description what the customer sees
     * @param returnUrl where to go after paying
     * @param cancelUrl where to go when the customer goes back
     * @param expiresAt when the page should stop accepting payment
     */
    record SessionRequest(String orderId, String orderNumber, long amountMinor, String currency, String description,
            String returnUrl, String cancelUrl, Instant expiresAt) {
    }

    /**
     * An opened page.
     *
     * @param sessionReference the gateway's id of the page
     * @param redirectUrl where to send the customer
     * @param expiresAt when it stops accepting payment
     */
    record HostedSession(String sessionReference, String redirectUrl, Instant expiresAt) {
    }

    /**
     * What a webhook says.
     *
     * @param outcome {@code SUCCEEDED}, {@code FAILED} or {@code EXPIRED}
     * @param orderId the order it is about
     * @param sessionReference the page it is about
     * @param paymentReference the gateway's id of the payment, used for refunds
     * @param amountMinor amount paid
     * @param reason why it failed, or {@code null}
     */
    record WebhookEvent(String outcome, String orderId, String sessionReference, String paymentReference, long amountMinor,
            String reason) {

        public static final String SUCCEEDED = "SUCCEEDED";

        public static final String FAILED = "FAILED";

        public static final String EXPIRED = "EXPIRED";
    }

    /** A gateway call failed. {@code retryable} when trying again later may work. */
    class GatewayException extends RuntimeException {

        private final boolean retryable;

        public GatewayException(String message, boolean retryable, Throwable cause) {
            super(message, cause);
            this.retryable = retryable;
        }

        public boolean isRetryable() {
            return this.retryable;
        }
    }
}
