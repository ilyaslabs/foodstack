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

    /** The payment of an order, or {@code null} when it has none (cash). */
    PaymentView get(@Param("orderId") String orderId);
}
