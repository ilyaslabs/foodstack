package io.github.ilyaslabs.foodstack.promotion.core.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.platform.core.PaymentMethod;

/**
 * A promotion as an operator or store owner enters it (PRD 4.8). Amounts are minor units, rates
 * basis points (100 = 1%).
 *
 * @param code 3 to 20 letters, digits or hyphens
 * @param title what customers see, by language; at least one language
 * @param type {@code PERCENT}, {@code FIXED} or {@code FREE_DELIVERY}
 * @param percentBasisPoints percent off, 1 to 10000 (PERCENT)
 * @param maxDiscountMinor cap of a percent discount, or {@code null}
 * @param amountMinor amount off, more than 0 (FIXED)
 * @param minOrderMinor smallest food subtotal, 0 for none
 * @param startsAt first moment it applies
 * @param endsAt moment it stops, or {@code null}
 * @param totalLimit uses in total, or {@code null}
 * @param perCustomerLimit uses per customer, or {@code null}
 * @param firstOrderOnly only a customer's first order
 * @param storeIds branches, empty for all
 * @param cities cities, empty for all (operator only)
 * @param paymentMethods payment methods, empty for all (operator only)
 * @param operatorMerchantOnly only on the operator's merchant account (operator only)
 * @param operatorFundingBasisPoints share the operator funds, 0 to 10000 (operator only)
 */
public record PromotionInput(String code, Map<String, String> title, String type, int percentBasisPoints, Long maxDiscountMinor,
        long amountMinor, long minOrderMinor, Instant startsAt, Instant endsAt, Integer totalLimit, Integer perCustomerLimit,
        boolean firstOrderOnly, List<String> storeIds, List<String> cities, List<PaymentMethod> paymentMethods,
        boolean operatorMerchantOnly, int operatorFundingBasisPoints) {
}
