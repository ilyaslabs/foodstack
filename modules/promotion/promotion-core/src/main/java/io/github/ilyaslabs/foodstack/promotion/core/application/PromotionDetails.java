package io.github.ilyaslabs.foodstack.promotion.core.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.platform.core.PaymentMethod;

/**
 * A promotion for the back office. See {@link PromotionInput} for the rules.
 *
 * @param id promotion id
 * @param owner {@code OPERATOR} or {@code STORE}
 * @param brandId brand of a store promotion
 * @param code code
 * @param title title by language
 * @param type type
 * @param percentBasisPoints percent off
 * @param maxDiscountMinor cap
 * @param amountMinor amount off
 * @param minOrderMinor minimum subtotal
 * @param startsAt start
 * @param endsAt end
 * @param totalLimit total uses
 * @param perCustomerLimit uses per customer
 * @param firstOrderOnly first order only
 * @param storeIds branches
 * @param cities cities
 * @param paymentMethods payment methods
 * @param operatorMerchantOnly operator account only
 * @param operatorFundingBasisPoints operator's share
 * @param active switched on
 * @param used uses held or redeemed
 * @param version for {@code If-Match}
 * @param updatedAt last change
 */
public record PromotionDetails(String id, String owner, String brandId, String code, Map<String, String> title, String type,
        int percentBasisPoints, Long maxDiscountMinor, long amountMinor, long minOrderMinor, Instant startsAt, Instant endsAt,
        Integer totalLimit, Integer perCustomerLimit, boolean firstOrderOnly, List<String> storeIds, List<String> cities,
        List<PaymentMethod> paymentMethods, boolean operatorMerchantOnly, int operatorFundingBasisPoints, boolean active,
        long used, long version, Instant updatedAt) {
}
