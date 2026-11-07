package io.github.ilyaslabs.foodstack.payment.core.gateway;

import java.time.Instant;
import java.util.Map;

/**
 * A card gateway (TDD 5.4). The customer types card details either on the gateway's own page
 * (hosted) or into the gateway's fields inside the app's checkout (embedded); either way card data
 * never touches FoodStack. Saved cards (PRD CO-5a) stay with the gateway too: FoodStack keeps only
 * the gateway's customer reference. Adapters exist for a local sandbox and for Stripe Checkout;
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

    /**
     * Checks that the credentials work with a harmless call, before a store's merchant account is
     * used (PRD DP-4). Throws {@link GatewayException} with the gateway's reason when they do not.
     */
    void verify();

    /** Gives money back. {@code idempotencyKey} makes a retried call safe. */
    String refund(String paymentReference, long amountMinor, String currency, String idempotencyKey);

    /** Whether the gateway can show its card fields inside the app's checkout. */
    default boolean supportsEmbedded() {
        return false;
    }

    /** The key the browser uses to show embedded fields, or {@code null} when none is needed. */
    default String publishableKey() {
        return null;
    }

    /** Whether the gateway can keep cards for returning customers. */
    default boolean supportsSavedCards() {
        return false;
    }

    /** Makes the gateway's customer record for a user; its reference is all FoodStack keeps. */
    default String createCustomer(String userId) {
        throw new GatewayException("Saved cards are not supported by " + id(), false, null);
    }

    /** The cards the gateway keeps for a customer. */
    default java.util.List<SavedCard> cards(String customerReference) {
        return java.util.List.of();
    }

    /** Forgets one saved card of a customer. */
    default void removeCard(String customerReference, String cardId) {
    }

    /** Forgets the customer and all their cards (account deletion, PRD ID-7). */
    default void deleteCustomer(String customerReference) {
    }

    /**
     * A card the gateway keeps for a customer: only what is printed on the card's face.
     *
     * @param id the gateway's id of the card
     * @param brand such as {@code visa}
     * @param last4 last four digits
     * @param expMonth expiry month
     * @param expYear expiry year
     */
    record SavedCard(String id, String brand, String last4, int expMonth, int expYear) {
    }

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
     * @param embedded show the gateway's fields inside the app instead of its own page
     * @param customerReference the gateway customer whose saved cards to offer and keep, or {@code null}
     */
    record SessionRequest(String orderId, String orderNumber, long amountMinor, String currency, String description,
            String returnUrl, String cancelUrl, Instant expiresAt, boolean embedded, String customerReference) {

        /** A hosted page without saved cards. */
        public SessionRequest(String orderId, String orderNumber, long amountMinor, String currency, String description,
                String returnUrl, String cancelUrl, Instant expiresAt) {
            this(orderId, orderNumber, amountMinor, currency, description, returnUrl, cancelUrl, expiresAt, false, null);
        }
    }

    /**
     * An opened page.
     *
     * @param sessionReference the gateway's id of the page
     * @param redirectUrl where to send the customer
     * @param expiresAt when it stops accepting payment
     * @param clientSecret what the browser needs to show embedded fields, or {@code null} for a hosted page
     */
    record HostedSession(String sessionReference, String redirectUrl, Instant expiresAt, String clientSecret) {

        /** A hosted page. */
        public HostedSession(String sessionReference, String redirectUrl, Instant expiresAt) {
            this(sessionReference, redirectUrl, expiresAt, null);
        }
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

    /** Opens the same gateway on a store's own merchant account (PRD DP-3), with its credentials. */
    @FunctionalInterface
    interface AccountGateways {

        /**
         * @param secretKey the account's secret API key
         * @param webhookSecret the signing secret of the account's webhook endpoint
         */
        CardGateway open(String secretKey, String webhookSecret);
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
