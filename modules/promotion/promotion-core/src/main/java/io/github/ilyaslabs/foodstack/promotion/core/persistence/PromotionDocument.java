package io.github.ilyaslabs.foodstack.promotion.core.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.platform.core.PaymentMethod;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A promotion in {@code promotion_promotions} (TDD 5.5); shard key {@code { _id: hashed }}. Its
 * code is unique through {@code promotion_codes}.
 *
 * @param id promotion id
 * @param owner {@code OPERATOR} or {@code STORE}
 * @param brandId the brand of a store promotion, {@code null} for the operator's
 * @param code the code customers type, upper case
 * @param title what customers see, by language
 * @param type {@code PERCENT}, {@code FIXED} or {@code FREE_DELIVERY}
 * @param percentBasisPoints percent off in basis points (PERCENT)
 * @param maxDiscountMinor cap of a percent discount, or {@code null}
 * @param amountMinor amount off (FIXED)
 * @param minOrderMinor smallest food subtotal, 0 for none
 * @param startsAt first moment it applies
 * @param endsAt moment it stops applying, or {@code null}
 * @param totalLimit uses in total, or {@code null} for no limit
 * @param perCustomerLimit uses per customer, or {@code null} for no limit
 * @param firstOrderOnly only for a customer's first order
 * @param storeIds branches it applies to, empty for all (of the brand, for a store promotion)
 * @param cities cities it applies to (operator promotions), empty for all
 * @param paymentMethods payment methods it applies to, empty for all (PRD PR-3a)
 * @param operatorMerchantOnly only when the payment goes to the operator's merchant account (PRD PR-3a)
 * @param operatorFundingBasisPoints share of the discount the operator funds; store promotions 0 (PRD PR-3)
 * @param active switched on
 * @param used uses held or redeemed
 * @param createdAt created
 * @param createdBy creator
 * @param updatedAt last change
 * @param updatedBy who changed it
 * @param version optimistic lock
 */
@Document("promotion_promotions")
public record PromotionDocument(@Id String id, String owner, String brandId, String code, Map<String, String> title, String type,
        int percentBasisPoints, Long maxDiscountMinor, long amountMinor, long minOrderMinor, Instant startsAt, Instant endsAt,
        Integer totalLimit, Integer perCustomerLimit, boolean firstOrderOnly, List<String> storeIds, List<String> cities,
        List<PaymentMethod> paymentMethods, boolean operatorMerchantOnly, int operatorFundingBasisPoints, boolean active,
        long used, Instant createdAt, String createdBy, Instant updatedAt, String updatedBy, @Version Long version) {

    public static final String OPERATOR = "OPERATOR";

    public static final String STORE = "STORE";

    public static final String PERCENT = "PERCENT";

    public static final String FIXED = "FIXED";

    public static final String FREE_DELIVERY = "FREE_DELIVERY";
}
