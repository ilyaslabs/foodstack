package io.github.ilyaslabs.foodstack.payment.api.events;

import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;

/**
 * The gateway refused a refund; support has to act. Topic {@code foodstack.payment.events}.
 *
 * @param orderId the order
 * @param refundId the refund
 * @param amountMinor amount asked for
 * @param reason the gateway's reason
 */
public record RefundFailed(String orderId, String refundId, long amountMinor, String reason) implements ModuleEvent {

    @Override
    public String subject() {
        return this.orderId;
    }
}
