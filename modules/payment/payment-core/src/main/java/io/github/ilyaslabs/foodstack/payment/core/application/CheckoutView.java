package io.github.ilyaslabs.foodstack.payment.core.application;

import java.util.List;

/**
 * What the app's own payment page needs to show the gateway's embedded fields (PRD CO-5).
 *
 * @param orderId the order
 * @param status {@code PENDING}, {@code SUCCEEDED}, {@code FAILED} or {@code EXPIRED}
 * @param amountMinor amount
 * @param currency ISO 4217 code
 * @param gateway {@code stripe} or {@code sandbox}
 * @param publishableKey the gateway's browser key, or {@code null}
 * @param clientSecret what the gateway's script needs, or {@code null} when the payment is closed
 * @param returnUrl where to go after paying
 * @param cancelUrl where Back goes
 * @param expiresAt when the payment stops being accepted, epoch seconds
 * @param canSaveCard whether the customer may keep the card for next time
 * @param savedCards the customer's saved cards, offered by the sandbox's pretend fields (a real
 *     gateway shows them in its own fields)
 */
public record CheckoutView(String orderId, String status, long amountMinor, String currency, String gateway,
        String publishableKey, String clientSecret, String returnUrl, String cancelUrl, long expiresAt, boolean canSaveCard,
        List<SavedCardView> savedCards) {
}
