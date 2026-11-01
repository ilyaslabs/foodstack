package io.github.ilyaslabs.foodstack.promotion.api.events;

import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;

/**
 * A coupon use was given back because its order was cancelled, rejected or failed. Topic
 * {@code foodstack.promotion.events}.
 *
 * @param orderId the order
 * @param promotionId the promotion
 * @param wasRedeemed whether the use had been redeemed (the order had been confirmed)
 */
public record CouponReleased(String orderId, String promotionId, boolean wasRedeemed) implements ModuleEvent {

    @Override
    public String subject() {
        return this.orderId;
    }
}
