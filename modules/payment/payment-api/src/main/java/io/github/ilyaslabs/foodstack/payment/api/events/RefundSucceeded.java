package io.github.ilyaslabs.foodstack.payment.api.events;

import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;

/**
 * Money went back to the customer's card. Topic {@code foodstack.payment.events}; the order
 * timeline shows it.
 *
 * @param orderId the order
 * @param refundId the refund
 * @param amountMinor amount given back
 * @param refundedTotalMinor everything given back for the order so far
 * @param currency ISO 4217 code
 */
public record RefundSucceeded(String orderId, String refundId, long amountMinor, long refundedTotalMinor, String currency)
        implements ModuleEvent {

    @Override
    public String subject() {
        return this.orderId;
    }
}
