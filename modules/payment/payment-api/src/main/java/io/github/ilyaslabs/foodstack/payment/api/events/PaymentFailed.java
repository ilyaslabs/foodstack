package io.github.ilyaslabs.foodstack.payment.api.events;

import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;

/**
 * A card payment failed or its page expired. Topic {@code foodstack.payment.events}; the order
 * moves to {@code FAILED}.
 *
 * @param orderId the order
 * @param reason {@code DECLINED}, {@code EXPIRED} or the gateway's reason
 */
public record PaymentFailed(String orderId, String reason) implements ModuleEvent {

    public static final String DECLINED = "DECLINED";

    public static final String EXPIRED = "EXPIRED";

    @Override
    public String subject() {
        return this.orderId;
    }
}
