package io.github.ilyaslabs.foodstack.promotion.web.dto;

import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.platform.core.PaymentMethod;

/**
 * {@code POST /api/v1/promotions} and {@code PUT /api/v1/promotions/{id}}. Times are epoch seconds,
 * amounts minor units, rates basis points (100 = 1%).
 *
 * @param brandId the brand of a store promotion; {@code null} for an operator promotion (create only)
 * @param code 3 to 20 letters, digits or hyphens
 * @param title what customers see, by language
 * @param type {@code PERCENT}, {@code FIXED} or {@code FREE_DELIVERY}
 * @param percentBasisPoints percent off (PERCENT)
 * @param maxDiscountMinor cap of a percent discount
 * @param amountMinor amount off (FIXED)
 * @param minOrderMinor smallest food subtotal
 * @param startsAt first moment it applies
 * @param endsAt moment it stops, or {@code null}
 * @param totalLimit uses in total, or {@code null}
 * @param perCustomerLimit uses per customer, or {@code null}
 * @param firstOrderOnly only a customer's first order
 * @param storeIds branches, empty for all
 * @param cities cities, empty for all (operator only)
 * @param paymentMethods payment methods, empty for all (operator only)
 * @param operatorMerchantOnly only when paid to the operator's merchant account (operator only)
 * @param operatorFundingBasisPoints share the operator funds (operator only)
 */
public record PromotionRequest(String brandId, String code, Map<String, String> title, String type, Integer percentBasisPoints,
        Long maxDiscountMinor, Long amountMinor, Long minOrderMinor, Long startsAt, Long endsAt, Integer totalLimit,
        Integer perCustomerLimit, Boolean firstOrderOnly, List<String> storeIds, List<String> cities,
        List<PaymentMethod> paymentMethods, Boolean operatorMerchantOnly, Integer operatorFundingBasisPoints) {
}
