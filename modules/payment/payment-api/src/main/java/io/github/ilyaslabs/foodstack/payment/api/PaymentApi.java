package io.github.ilyaslabs.foodstack.payment.api;

import io.github.ilyaslabs.moduleswitch.Param;

/**
 * What other modules may ask the payment module (TDD 5.4). The order starts a card payment at
 * placement and sends the customer to the gateway's hosted page; the outcome comes back as
 * {@code PaymentSucceeded} or {@code PaymentFailed}. Refunds follow the order events.
 */
public interface PaymentApi {

    /** Which payment methods checkout may offer: the operator's switches and whether a card gateway is set up. */
    PaymentOptionsView options();

    /**
     * Starts the card payment of an order and returns where to send the customer. Calling it again
     * for the same order returns the same session. Errors: {@code PAYMENT_UNAVAILABLE} (400) when
     * card payments are off or no gateway is set up.
     */
    CardSessionView startCard(@Param("request") StartCardRequest request);

    /**
     * Whose merchant account a card payment for the branch goes into now (PRD DP-4, DP-7, BB-9, TDD
     * 5.4): the branch's own, else its brand's, when direct collection is on for the deployment and
     * the branch, the account is verified and the branch is within its credit limit; otherwise the
     * operator's. The order keeps the answer, so refunds go back through the same account.
     */
    MerchantView resolveMerchant(@Param("storeId") String storeId);

    /** The payment of an order, or {@code null} when it has none (cash). */
    PaymentView get(@Param("orderId") String orderId);
}
