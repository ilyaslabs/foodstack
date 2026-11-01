package io.github.ilyaslabs.foodstack.promotion.api;

import java.util.Map;

/**
 * The result of checking a coupon (PRD CO-4: a failure explains why).
 *
 * @param applies whether the coupon applies
 * @param reason why not, one of the constants; {@code null} when it applies
 * @param details values for the explanation, such as {@code minOrderMinor}; empty when none
 * @param promotionId the promotion behind the code, or {@code null} when the code is unknown
 * @param code the code in upper case
 * @param title what the customer sees, by language
 * @param itemsDiscountMinor taken off the food subtotal
 * @param deliveryDiscountMinor taken off the delivery charge (free delivery)
 * @param operatorShareMinor part of the total discount the operator funds; the store funds the rest
 */
public record CouponEvaluation(boolean applies, String reason, Map<String, Object> details, String promotionId, String code,
        Map<String, String> title, long itemsDiscountMinor, long deliveryDiscountMinor, long operatorShareMinor) {

    public static final String NOT_FOUND = "COUPON_NOT_FOUND";

    public static final String INACTIVE = "COUPON_INACTIVE";

    public static final String NOT_STARTED = "COUPON_NOT_STARTED";

    public static final String EXPIRED = "COUPON_EXPIRED";

    public static final String MIN_ORDER = "COUPON_MIN_ORDER";

    public static final String NOT_FOR_STORE = "COUPON_NOT_FOR_STORE";

    public static final String USED_UP = "COUPON_USED_UP";

    public static final String CUSTOMER_LIMIT = "COUPON_CUSTOMER_LIMIT";

    public static final String FIRST_ORDER_ONLY = "COUPON_FIRST_ORDER_ONLY";

    public static final String PAYMENT_METHOD = "COUPON_PAYMENT_METHOD";

    public static final String MERCHANT = "COUPON_MERCHANT";

    public static final String DELIVERY_ONLY = "COUPON_DELIVERY_ONLY";

    /** The whole discount. */
    public long totalDiscountMinor() {
        return this.itemsDiscountMinor + this.deliveryDiscountMinor;
    }

    public static CouponEvaluation rejected(String code, String promotionId, String reason, Map<String, Object> details) {
        return new CouponEvaluation(false, reason, details, promotionId, code, Map.of(), 0, 0, 0);
    }
}
