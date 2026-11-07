package io.github.ilyaslabs.foodstack.order.api;

import io.github.ilyaslabs.foodstack.geo.api.DeliveryMode;

/**
 * The money of an order as it stands, for the ledger (PRD FI-1, TDD 5.4): what the customer paid,
 * split into its parts, who funds the discount, the commission snapshotted at placement, and who
 * delivers and who collected the money. Amounts are minor units of the order's currency.
 *
 * @param brandId the branch's brand, for brand roll-ups (PRD BB-8)
 * @param deliveryMode who delivers now ({@code SELF} after support handed an operator delivery back), or {@code null}
 *        for pickup
 * @param collectedBy {@code OPERATOR} when the money reached the operator's account (or its rider, for cash), or
 *        {@code STORE} when it reached the store's own merchant account (TDD 5.4)
 * @param subtotalMinor food before discounts
 * @param itemsDiscountMinor coupon discount on the food
 * @param deliveryChargeMinor delivery charge before discounts
 * @param deliveryDiscountMinor coupon discount on delivery
 * @param serviceFeeMinor the operator's service fee
 * @param taxMinor tax, which goes to the store
 * @param tipMinor the customer's tip
 * @param operatorDiscountMinor the part of the whole discount the operator funds; the store funds the rest
 * @param commissionBasisPoints the branch's commission percentage when ordering, in hundredths of a percent
 * @param commissionFixedMinor the branch's fixed commission per order when ordering
 */
public record OrderMoney(String brandId, DeliveryMode deliveryMode, String collectedBy, long subtotalMinor,
        long itemsDiscountMinor, long deliveryChargeMinor, long deliveryDiscountMinor, long serviceFeeMinor, long taxMinor,
        long tipMinor, long operatorDiscountMinor, int commissionBasisPoints, long commissionFixedMinor) {

    /** The money reached the operator. */
    public static final String OPERATOR = "OPERATOR";

    /** The money reached the store's own merchant account (direct collection). */
    public static final String STORE = "STORE";

    /** What the customer pays. */
    public long totalMinor() {
        return this.subtotalMinor - this.itemsDiscountMinor + this.deliveryChargeMinor - this.deliveryDiscountMinor
                + this.serviceFeeMinor + this.taxMinor + this.tipMinor;
    }
}
