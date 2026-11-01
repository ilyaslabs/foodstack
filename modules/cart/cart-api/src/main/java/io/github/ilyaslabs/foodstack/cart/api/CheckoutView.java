package io.github.ilyaslabs.foodstack.cart.api;

import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.geo.api.DeliveryMode;
import io.github.ilyaslabs.foodstack.platform.core.Fulfilment;
import io.github.ilyaslabs.foodstack.platform.core.PaymentMethod;
import io.github.ilyaslabs.foodstack.promotion.api.CouponEvaluation;
import io.github.ilyaslabs.foodstack.store.api.OrderValidationView;

/**
 * A priced cart (PRD CO-2). Amounts are minor units of {@code currency}. The order can be placed
 * only when {@code problems} is empty.
 *
 * @param customerId whose cart
 * @param cartVersion version of the cart that was priced
 * @param store the branch, or {@code null} for an empty cart
 * @param currency ISO 4217 code
 * @param fulfilment delivery or pickup
 * @param lines priced lines in cart order
 * @param subtotalMinor food subtotal
 * @param delivery the delivery price, or {@code null} for pickup
 * @param coupon the coupon result, or {@code null} without a code
 * @param itemsDiscountMinor coupon discount on food
 * @param deliveryDiscountMinor coupon discount on delivery
 * @param deliveryChargeMinor delivery charge after a free-delivery threshold, before the coupon
 * @param serviceFeeMinor operator service fee
 * @param taxMinor tax on food after discount
 * @param tipMinor tip for the rider
 * @param totalMinor what the customer pays
 * @param etaMinutes preparation plus travel time
 * @param paymentMethods methods the operator allows (card also needs a working gateway at placement)
 * @param tipsEnabled whether a tip may be added
 * @param problems what stops the order, empty when it can be placed
 */
public record CheckoutView(String customerId, long cartVersion, OrderValidationView.StoreSnapshot store, String currency,
        Fulfilment fulfilment, List<Line> lines, long subtotalMinor, Delivery delivery, CouponEvaluation coupon,
        long itemsDiscountMinor, long deliveryDiscountMinor, long deliveryChargeMinor, long serviceFeeMinor, long taxMinor,
        long tipMinor, long totalMinor, int etaMinutes, List<PaymentMethod> paymentMethods, boolean tipsEnabled,
        List<Problem> problems) {

    public static final String CART_EMPTY = "CART_EMPTY";

    public static final String STORE_NOT_LIVE = "STORE_NOT_LIVE";

    public static final String STORE_CLOSED = "STORE_CLOSED";

    public static final String PICKUP_UNAVAILABLE = "PICKUP_UNAVAILABLE";

    public static final String ADDRESS_REQUIRED = "ADDRESS_REQUIRED";

    public static final String NOT_DELIVERABLE = "NOT_DELIVERABLE";

    public static final String MIN_ORDER = "MIN_ORDER";

    public static final String LINE_PROBLEM = "LINE_PROBLEM";

    public static final String COUPON_INVALID = "COUPON_INVALID";

    public static final String PAYMENT_METHOD_UNAVAILABLE = "PAYMENT_METHOD_UNAVAILABLE";

    public static final String TIP_INVALID = "TIP_INVALID";

    /**
     * A cart line with its price.
     *
     * @param lineId stable id of the line in the cart
     * @param note the customer's note for the kitchen, or {@code null}
     * @param priced the line priced by the store
     */
    public record Line(String lineId, String note, OrderValidationView.PricedLine priced) {
    }

    /**
     * The delivery price.
     *
     * @param deliverable whether the store delivers to the address
     * @param reason why not, as the geo module reports it
     * @param deliveryMode who delivers
     * @param chargeMinor charge before the free-delivery threshold
     * @param minOrderMinor smallest subtotal accepted
     * @param freeDeliveryAboveMinor subtotal from which delivery is free, or {@code null}
     * @param distanceMeters estimated road distance
     */
    public record Delivery(boolean deliverable, String reason, DeliveryMode deliveryMode, long chargeMinor, long minOrderMinor,
            Long freeDeliveryAboveMinor, long distanceMeters) {
    }

    /**
     * Something that stops the order.
     *
     * @param code one of the constants of {@link CheckoutView}
     * @param details values for the message, such as the line index or the coupon reason
     */
    public record Problem(String code, Map<String, Object> details) {
    }

    /** Whether the order can be placed as priced. */
    public boolean orderable() {
        return this.problems.isEmpty();
    }
}
