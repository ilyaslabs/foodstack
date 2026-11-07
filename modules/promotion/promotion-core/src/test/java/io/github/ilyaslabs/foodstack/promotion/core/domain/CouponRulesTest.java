package io.github.ilyaslabs.foodstack.promotion.core.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.platform.core.Fulfilment;
import io.github.ilyaslabs.foodstack.platform.core.PaymentMethod;
import io.github.ilyaslabs.foodstack.promotion.api.CouponEvaluation;
import io.github.ilyaslabs.foodstack.promotion.api.CouponRequest;
import io.github.ilyaslabs.foodstack.promotion.core.persistence.PromotionDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CouponRulesTest {

    private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");

    @Test
    @DisplayName("percent off comes off food, capped; the operator funds its share")
    void percent() {
        CouponEvaluation result = CouponRules.evaluate(promotion("OPERATOR", null, "PERCENT", 2000, 30_000L, 0), order(200_000),
                0, 0, NOW);

        assertThat(result.applies()).isTrue();
        assertThat(result.itemsDiscountMinor()).isEqualTo(30_000);
        assertThat(result.deliveryDiscountMinor()).isZero();
        assertThat(result.operatorShareMinor()).isEqualTo(15_000);
        assertThat(CouponRules.evaluate(promotion("OPERATOR", null, "PERCENT", 2000, null, 0), order(100_000), 0, 0, NOW)
            .itemsDiscountMinor()).isEqualTo(20_000);
    }

    @Test
    @DisplayName("a fixed amount never exceeds the food subtotal; free delivery takes the whole charge; store promotions are store-funded")
    void fixedAndFreeDelivery() {
        assertThat(CouponRules.evaluate(promotion("STORE", "b1", "FIXED", 0, null, 0), order(30_000), 0, 0, NOW)
            .itemsDiscountMinor()).isEqualTo(30_000);
        CouponEvaluation free = CouponRules.evaluate(promotion("STORE", "b1", "FREE_DELIVERY", 0, null, 0), order(100_000), 0, 0,
                NOW);
        assertThat(free.deliveryDiscountMinor()).isEqualTo(15_000);
        assertThat(free.operatorShareMinor()).isZero();
    }

    @Test
    @DisplayName("explains each reason a coupon does not apply (PRD CO-4)")
    void reasons() {
        PromotionDocument p = promotion("OPERATOR", null, "PERCENT", 1000, null, 0);
        assertThat(reason(with(p, "active", false), order(100_000), 0, 0)).isEqualTo(CouponEvaluation.INACTIVE);
        assertThat(reason(with(p, "startsAt", NOW.plusSeconds(60)), order(100_000), 0, 0)).isEqualTo(CouponEvaluation.NOT_STARTED);
        assertThat(reason(with(p, "endsAt", NOW), order(100_000), 0, 0)).isEqualTo(CouponEvaluation.EXPIRED);
        assertThat(reason(with(p, "storeIds", List.of("s9")), order(100_000), 0, 0)).isEqualTo(CouponEvaluation.NOT_FOR_STORE);
        assertThat(reason(with(p, "cities", List.of("Karachi")), order(100_000), 0, 0)).isEqualTo(CouponEvaluation.NOT_FOR_STORE);
        assertThat(reason(with(p, "cities", List.of(" lahore ")), order(100_000), 0, 0)).isNull();
        // M4: cities are the operator's city ids
        CouponRequest inCity = new CouponRequest("SAVE", "c1", "s1", "b1", "Lahore", 100_000, 15_000, Fulfilment.DELIVERY,
                PaymentMethod.CASH, true, "PKR", "city-lhr");
        assertThat(reason(with(p, "cities", List.of("city-lhr")), inCity, 0, 0)).isNull();
        assertThat(reason(with(p, "cities", List.of("city-khi")), inCity, 0, 0)).isEqualTo(CouponEvaluation.NOT_FOR_STORE);
        assertThat(reason(with(p, "paymentMethods", List.of(PaymentMethod.CARD)), order(100_000), 0, 0))
            .isEqualTo(CouponEvaluation.PAYMENT_METHOD);
        assertThat(reason(with(p, "operatorMerchantOnly", true), new CouponRequest("SAVE", "c1", "s1", "b1", "Lahore", 100_000,
                15_000, Fulfilment.DELIVERY, PaymentMethod.CASH, false, "PKR"), 0, 0)).isEqualTo(CouponEvaluation.MERCHANT);
        assertThat(reason(with(p, "minOrderMinor", 150_000L), order(100_000), 0, 0)).isEqualTo(CouponEvaluation.MIN_ORDER);
        assertThat(reason(with(with(p, "totalLimit", 5), "used", 5L), order(100_000), 0, 0)).isEqualTo(CouponEvaluation.USED_UP);
        assertThat(reason(with(p, "perCustomerLimit", 1), order(100_000), 1, 0)).isEqualTo(CouponEvaluation.CUSTOMER_LIMIT);
        assertThat(reason(with(p, "firstOrderOnly", true), order(100_000), 0, 2)).isEqualTo(CouponEvaluation.FIRST_ORDER_ONLY);
        assertThat(reason(promotion("STORE", "b2", "FIXED", 0, null, 0), order(100_000), 0, 0))
            .isEqualTo(CouponEvaluation.NOT_FOR_STORE);
        assertThat(reason(promotion("OPERATOR", null, "FREE_DELIVERY", 0, null, 0), new CouponRequest("SAVE", "c1", "s1", "b1",
                "Lahore", 100_000, 0, Fulfilment.PICKUP, null, true, "PKR"), 0, 0)).isEqualTo(CouponEvaluation.DELIVERY_ONLY);
    }

    @Test
    @DisplayName("codes are matched trimmed and in upper case")
    void normalize() {
        assertThat(CouponRules.normalize("  welcome-10 ")).isEqualTo("WELCOME-10");
        assertThat(CouponRules.normalize(null)).isEmpty();
    }

    private static String reason(PromotionDocument p, CouponRequest request, long uses, long orders) {
        return CouponRules.evaluate(p, request, uses, orders, NOW).reason();
    }

    private static CouponRequest order(long subtotal) {
        return new CouponRequest("SAVE", "c1", "s1", "b1", "Lahore", subtotal, 15_000, Fulfilment.DELIVERY, PaymentMethod.CASH,
                true, "PKR");
    }

    static PromotionDocument promotion(String owner, String brandId, String type, int percent, Long cap, long minOrder) {
        return new PromotionDocument("p1", owner, brandId, "SAVE", Map.of("en", "Save"), type, percent, cap, 50_000, minOrder,
                NOW.minusSeconds(3600), null, null, null, false, List.of(), List.of(), List.of(), false,
                "OPERATOR".equals(owner) ? 5000 : 0, true, 0, NOW, "admin", NOW, "admin", 1L);
    }

    /** A copy with one component changed, by name. */
    static PromotionDocument with(PromotionDocument p, String field, Object value) {
        var components = PromotionDocument.class.getRecordComponents();
        Object[] values = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];
        try {
            for (int i = 0; i < components.length; i++) {
                types[i] = components[i].getType();
                values[i] = components[i].getName().equals(field) ? value : components[i].getAccessor().invoke(p);
            }
            return PromotionDocument.class.getDeclaredConstructor(types).newInstance(values);
        }
        catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
