package io.github.ilyaslabs.foodstack.promotion.core.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;

import io.github.ilyaslabs.foodstack.platform.core.Fulfilment;
import io.github.ilyaslabs.foodstack.promotion.api.CouponEvaluation;
import io.github.ilyaslabs.foodstack.promotion.api.CouponRequest;
import io.github.ilyaslabs.foodstack.promotion.core.persistence.PromotionDocument;

/**
 * Whether a promotion applies to an order and what it takes off (PRD PR-1 to PR-3a), as one pure
 * function. Checks run in the order a customer can act on: is the code live, is it for this store,
 * does the order qualify, are uses left. Percent and fixed discounts come off the food subtotal;
 * free delivery comes off the delivery charge.
 */
public final class CouponRules {

    private CouponRules() {
    }

    /**
     * @param promotion the promotion behind the code
     * @param request the order
     * @param customerUses how many times this customer holds or used it
     * @param customerConfirmedOrders how many orders the customer has had confirmed
     * @param now the current time
     */
    public static CouponEvaluation evaluate(PromotionDocument promotion, CouponRequest request, long customerUses,
            long customerConfirmedOrders, Instant now) {
        String code = promotion.code();
        String id = promotion.id();
        if (!promotion.active()) {
            return CouponEvaluation.rejected(code, id, CouponEvaluation.INACTIVE, Map.of());
        }
        if (now.isBefore(promotion.startsAt())) {
            return CouponEvaluation.rejected(code, id, CouponEvaluation.NOT_STARTED,
                    Map.of("startsAt", promotion.startsAt().getEpochSecond()));
        }
        if (promotion.endsAt() != null && !now.isBefore(promotion.endsAt())) {
            return CouponEvaluation.rejected(code, id, CouponEvaluation.EXPIRED, Map.of());
        }
        if (!appliesToStore(promotion, request)) {
            return CouponEvaluation.rejected(code, id, CouponEvaluation.NOT_FOR_STORE, Map.of());
        }
        if (PromotionDocument.FREE_DELIVERY.equals(promotion.type()) && request.fulfilment() == Fulfilment.PICKUP) {
            return CouponEvaluation.rejected(code, id, CouponEvaluation.DELIVERY_ONLY, Map.of());
        }
        if (!promotion.paymentMethods().isEmpty() && request.paymentMethod() != null
                && !promotion.paymentMethods().contains(request.paymentMethod())) {
            return CouponEvaluation.rejected(code, id, CouponEvaluation.PAYMENT_METHOD,
                    Map.of("paymentMethods", promotion.paymentMethods().stream().map(Enum::name).toList()));
        }
        if (promotion.operatorMerchantOnly() && !request.operatorMerchant()) {
            return CouponEvaluation.rejected(code, id, CouponEvaluation.MERCHANT, Map.of());
        }
        if (request.subtotalMinor() < promotion.minOrderMinor()) {
            return CouponEvaluation.rejected(code, id, CouponEvaluation.MIN_ORDER,
                    Map.of("minOrderMinor", promotion.minOrderMinor()));
        }
        if (promotion.totalLimit() != null && promotion.used() >= promotion.totalLimit()) {
            return CouponEvaluation.rejected(code, id, CouponEvaluation.USED_UP, Map.of());
        }
        if (promotion.perCustomerLimit() != null && customerUses >= promotion.perCustomerLimit()) {
            return CouponEvaluation.rejected(code, id, CouponEvaluation.CUSTOMER_LIMIT,
                    Map.of("perCustomerLimit", promotion.perCustomerLimit()));
        }
        if (promotion.firstOrderOnly() && customerConfirmedOrders > 0) {
            return CouponEvaluation.rejected(code, id, CouponEvaluation.FIRST_ORDER_ONLY, Map.of());
        }
        long items = 0;
        long delivery = 0;
        switch (promotion.type()) {
            case PromotionDocument.PERCENT -> {
                items = share(request.subtotalMinor(), promotion.percentBasisPoints());
                if (promotion.maxDiscountMinor() != null) {
                    items = Math.min(items, promotion.maxDiscountMinor());
                }
            }
            case PromotionDocument.FIXED -> items = Math.min(promotion.amountMinor(), request.subtotalMinor());
            case PromotionDocument.FREE_DELIVERY -> delivery = Math.max(request.deliveryChargeMinor(), 0);
            default -> throw new IllegalStateException("Unknown promotion type " + promotion.type());
        }
        long operatorShare = PromotionDocument.OPERATOR.equals(promotion.owner())
                ? share(items + delivery, promotion.operatorFundingBasisPoints()) : 0;
        return new CouponEvaluation(true, null, Map.of(), id, code, promotion.title(), items, delivery, operatorShare);
    }

    static boolean appliesToStore(PromotionDocument promotion, CouponRequest request) {
        if (PromotionDocument.STORE.equals(promotion.owner()) && !promotion.brandId().equals(request.brandId())) {
            return false;
        }
        if (!promotion.storeIds().isEmpty() && !promotion.storeIds().contains(request.storeId())) {
            return false;
        }
        if (!promotion.cities().isEmpty()) {
            // cities are the operator's city ids since M4; coupons made before keep matching by city name
            String city = (request.city() != null) ? request.city().strip().toLowerCase(Locale.ROOT) : "";
            return promotion.cities().stream().anyMatch((c) -> c.equals(request.cityId())
                    || c.strip().toLowerCase(Locale.ROOT).equals(city));
        }
        return true;
    }

    /** {@code amount * basisPoints / 10000}, rounded half up. */
    static long share(long amount, int basisPoints) {
        if (amount <= 0 || basisPoints <= 0) {
            return 0;
        }
        return (amount * basisPoints + 5000) / 10000;
    }

    /** A code in its stored form: trimmed, upper case. */
    public static String normalize(String code) {
        return (code != null) ? code.strip().toUpperCase(Locale.ROOT) : "";
    }
}
