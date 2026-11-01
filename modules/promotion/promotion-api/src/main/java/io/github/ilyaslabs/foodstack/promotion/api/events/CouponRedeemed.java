package io.github.ilyaslabs.foodstack.promotion.api.events;

import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;

/**
 * A coupon was used on a confirmed order. Topic {@code foodstack.promotion.events}; the ledger
 * (M4) records who funds the discount.
 *
 * @param orderId the order
 * @param promotionId the promotion
 * @param code the code used
 * @param customerId who used it
 * @param storeId the branch
 * @param discountMinor whole discount
 * @param operatorShareMinor part funded by the operator
 * @param currency ISO 4217 code
 */
public record CouponRedeemed(String orderId, String promotionId, String code, String customerId, String storeId,
        long discountMinor, long operatorShareMinor, String currency) implements ModuleEvent {

    @Override
    public String subject() {
        return this.orderId;
    }
}
