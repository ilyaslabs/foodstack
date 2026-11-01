package io.github.ilyaslabs.foodstack.cart.core.application;

/**
 * The money rules of checkout (PRD CO-2), kept apart so they are easy to read and test. Rates are
 * basis points (100 = 1%); every share rounds half up to a whole minor unit.
 */
public final class CheckoutMath {

    /** Average rider speed used for the delivery part of the ETA, as the store page uses. */
    static final double RIDER_KMH = 20;

    private CheckoutMath() {
    }

    /** {@code amount * basisPoints / 10000}, rounded half up. */
    public static long share(long amount, int basisPoints) {
        if (amount <= 0 || basisPoints <= 0) {
            return 0;
        }
        return Math.addExact(Math.multiplyExact(amount, basisPoints), 5000) / 10000;
    }

    /** Delivery charge after the free-delivery threshold. */
    public static long deliveryCharge(long chargeMinor, Long freeDeliveryAboveMinor, long subtotalMinor) {
        return (freeDeliveryAboveMinor != null && subtotalMinor >= freeDeliveryAboveMinor) ? 0 : chargeMinor;
    }

    /** Fixed part plus a share of the food subtotal. */
    public static long serviceFee(long fixedMinor, int basisPoints, long subtotalMinor) {
        return Math.addExact(fixedMinor, share(subtotalMinor, basisPoints));
    }

    /** Tax on the food subtotal after its discount. */
    public static long tax(long subtotalMinor, long itemsDiscountMinor, int basisPoints) {
        return share(Math.max(subtotalMinor - itemsDiscountMinor, 0), basisPoints);
    }

    /** Preparation plus riding time for the distance (0 for pickup). */
    public static int etaMinutes(int prepMinutes, long distanceMeters) {
        int travel = (distanceMeters > 0) ? (int) Math.ceil(distanceMeters / 1000.0 / RIDER_KMH * 60) : 0;
        return prepMinutes + travel;
    }
}
