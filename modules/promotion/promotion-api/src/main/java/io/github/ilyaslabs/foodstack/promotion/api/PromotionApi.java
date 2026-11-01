package io.github.ilyaslabs.foodstack.promotion.api;

import io.github.ilyaslabs.moduleswitch.Param;

/**
 * What other modules may ask the promotion module. The cart evaluates a coupon for its price
 * preview; the order reserves it at placement, so usage limits hold even when two orders race,
 * and releases it if the placement fails. Redemption on confirmation and release on cancellation
 * follow the order events (TDD 5.3, 5.5).
 */
public interface PromotionApi {

    /** Whether a coupon applies to this order and what it takes off. No side effects. */
    CouponEvaluation evaluate(@Param("request") CouponRequest request);

    /**
     * Evaluates the coupon again and, when it applies, holds one use of it for the order. Calling
     * it again for the same order returns the same hold.
     */
    CouponEvaluation reserve(@Param("orderId") String orderId, @Param("request") CouponRequest request);

    /** Gives back the use held for an order that was never placed. Does nothing when none is held. */
    void release(@Param("orderId") String orderId);
}
