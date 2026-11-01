package io.github.ilyaslabs.foodstack.promotion.core.persistence;

import java.time.Instant;
import java.util.Map;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One coupon use, keyed by the order ({@code promotion_redemptions}, shard key {@code { _id: hashed }}).
 *
 * @param id the order id
 * @param promotionId the promotion
 * @param code the code used
 * @param title what the customer saw
 * @param customerId who used it
 * @param storeId the branch
 * @param status {@code RESERVED}, {@code REDEEMED} or {@code RELEASED}
 * @param itemsDiscountMinor discount on food
 * @param deliveryDiscountMinor discount on delivery
 * @param operatorShareMinor part the operator funds
 * @param currency ISO 4217 code
 * @param reservedAt when the order was placed
 * @param redeemedAt when the order was confirmed, or {@code null}
 * @param releasedAt when the use was given back, or {@code null}
 */
@Document("promotion_redemptions")
public record RedemptionDocument(@Id String id, String promotionId, String code, Map<String, String> title, String customerId,
        String storeId, String status, long itemsDiscountMinor, long deliveryDiscountMinor, long operatorShareMinor,
        String currency, Instant reservedAt, Instant redeemedAt, Instant releasedAt) {

    public static final String RESERVED = "RESERVED";

    public static final String REDEEMED = "REDEEMED";

    public static final String RELEASED = "RELEASED";
}
