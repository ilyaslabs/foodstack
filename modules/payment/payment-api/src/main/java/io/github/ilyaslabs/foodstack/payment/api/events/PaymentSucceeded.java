package io.github.ilyaslabs.foodstack.payment.api.events;

import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;

/**
 * The gateway confirmed a card payment. Topic {@code foodstack.payment.events}; the order moves to
 * {@code CONFIRMED}.
 *
 * @param orderId the order
 * @param amountMinor amount charged
 * @param currency ISO 4217 code
 * @param gateway gateway id
 * @param gatewayReference the gateway's id of the payment
 */
public record PaymentSucceeded(String orderId, long amountMinor, String currency, String gateway, String gatewayReference)
        implements ModuleEvent {

    @Override
    public String subject() {
        return this.orderId;
    }
}
