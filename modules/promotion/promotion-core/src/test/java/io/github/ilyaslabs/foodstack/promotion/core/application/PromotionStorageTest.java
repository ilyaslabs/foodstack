package io.github.ilyaslabs.foodstack.promotion.core.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.platform.core.Fulfilment;
import io.github.ilyaslabs.foodstack.platform.core.Ids;
import io.github.ilyaslabs.foodstack.platform.core.PaymentMethod;
import io.github.ilyaslabs.foodstack.platform.mongo.FoodstackMongoProperties;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMigrations;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongo;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongoFactory;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.platform.test.FoodstackContainers;
import io.github.ilyaslabs.foodstack.promotion.api.CouponEvaluation;
import io.github.ilyaslabs.foodstack.promotion.api.CouponRequest;
import io.github.ilyaslabs.foodstack.promotion.core.PromotionConfiguration;
import io.github.ilyaslabs.foodstack.promotion.core.persistence.RedemptionDocument;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Coupon uses and promotion edits against a real two-shard MongoDB (the lowest layer, TDD 15.3). */
@SpringBootTest(classes = PromotionStorageTest.App.class, properties = "spring.data.mongodb.repositories.type=none")
class PromotionStorageTest {

    private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {

        @Bean
        ModuleMongo promotionMongo(ModuleMongoFactory factory) {
            return factory.create("promotion");
        }

        @Bean
        ModuleMigrations promotionMigrations(ModuleMongo promotionMongo, ModuleMongoFactory factory,
                FoodstackMongoProperties properties) {
            return new ModuleMigrations(promotionMongo, factory.connectionString("promotion"),
                    PromotionConfiguration.shardKeys(), properties);
        }
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        FoodstackContainers.registerMongo(registry);
    }

    @Autowired
    ModuleMongo promotionMongo;

    private PromotionService promotions;

    private CouponService coupons;

    private OutboxWriter outbox;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        this.outbox = new OutboxWriter(this.promotionMongo, JsonMapper.builder().build(), clock);
        this.promotions = new PromotionService(this.promotionMongo.template(), this.promotionMongo.transactionTemplate(), clock);
        this.coupons = new CouponService(this.promotionMongo.template(), this.promotionMongo.transactionTemplate(), this.outbox,
                clock);
    }

    @Test
    @DisplayName("codes are unique across promotions, any case; edits need the current version and keep the use count")
    void create() {
        String code = code();
        PromotionDetails created = this.promotions.create(null, input(code.toLowerCase(), 2, null), "admin-1");
        assertThat(created.code()).isEqualTo(code);
        assertThat(created.version()).isZero();
        assertThatThrownBy(() -> this.promotions.create("b1", input(code, null, null), "owner-1"))
            .isInstanceOfSatisfying(ModuleException.class, (ex) -> assertThat(ex.code()).isEqualTo("COUPON_CODE_TAKEN"));

        this.coupons.reserve(Ids.newId(), request(code, "c1"));
        String renamed = code();
        PromotionDetails updated = this.promotions.update(created.id(), input(renamed, 3, null), 0, "admin-1");
        assertThat(updated.used()).isEqualTo(1);
        assertThat(updated.totalLimit()).isEqualTo(3);
        assertThat(this.coupons.evaluate(request(renamed, "c2")).applies()).isTrue();
        assertThat(this.coupons.evaluate(request(code, "c2")).reason()).isEqualTo(CouponEvaluation.NOT_FOUND);
        assertThatThrownBy(() -> this.promotions.update(created.id(), input(renamed, 3, null), 0, "admin-1"))
            .isInstanceOfSatisfying(ModuleException.class, (ex) -> assertThat(ex.code()).isEqualTo("PROMOTION_VERSION_CONFLICT"));

        PromotionDetails store = this.promotions.create("brand-x", input(code(), null, null), "owner-1");
        assertThat(store.owner()).isEqualTo("STORE");
        assertThat(store.operatorFundingBasisPoints()).isZero();
        assertThat(this.promotions.list(List.of("brand-x"), 10)).extracting(PromotionDetails::id).containsExactly(store.id());
        assertThat(this.promotions.setActive(store.id(), false, "owner-1").active()).isFalse();
    }

    @Test
    @DisplayName("holds uses up to the total limit, returns the same hold for a repeated order, and frees it on release")
    void totalLimit() {
        String code = code();
        String promotionId = this.promotions.create(null, input(code, 1, null), "admin-1").id();
        String first = Ids.newId();

        assertThat(this.coupons.reserve(first, request(code, "c1")).applies()).isTrue();
        assertThat(this.coupons.reserve(first, request(code, "c1")).itemsDiscountMinor()).isEqualTo(10_000);
        assertThat(this.coupons.reserve(Ids.newId(), request(code, "c2")).reason()).isEqualTo(CouponEvaluation.USED_UP);
        assertThat(this.promotions.details(promotionId).used()).isEqualTo(1);

        this.coupons.release(first);
        this.coupons.release(first);
        assertThat(this.promotions.details(promotionId).used()).isZero();
        assertThat(this.coupons.reserve(Ids.newId(), request(code, "c2")).applies()).isTrue();
    }

    @Test
    @DisplayName("holds per-customer uses, redeems on confirmation with an event, and counts first orders")
    void perCustomer() {
        String code = code();
        this.promotions.create(null, input(code, null, 1), "admin-1");
        String order = Ids.newId();
        String customer = Ids.newId();

        assertThat(this.coupons.reserve(order, request(code, customer)).applies()).isTrue();
        assertThat(this.coupons.reserve(Ids.newId(), request(code, customer)).reason())
            .isEqualTo(CouponEvaluation.CUSTOMER_LIMIT);

        this.coupons.redeem(order);
        assertThat(this.promotionMongo.template().findById(order, RedemptionDocument.class).status())
            .isEqualTo(RedemptionDocument.REDEEMED);
        assertThat(this.promotionMongo.template()
            .find(Query.query(Criteria.where("subject").is(order)), Document.class, "promotion_outbox"))
            .extracting((d) -> d.getString("type"))
            .containsExactly("foodstack.promotion.CouponRedeemed.v1");
        assertThat(this.coupons.customerOf(order)).isEqualTo(customer);

        String firstOnly = code();
        this.promotions.create(null, new PromotionInput(firstOnly, Map.of("en", "First"), "FIXED", 0, null, 5_000, 0,
                NOW.minusSeconds(60), null, null, null, true, List.of(), List.of(), List.of(), false, 0), "admin-1");
        assertThat(this.coupons.evaluate(request(firstOnly, customer)).applies()).isTrue();
        this.coupons.recordConfirmedOrder(customer);
        assertThat(this.coupons.evaluate(request(firstOnly, customer)).reason()).isEqualTo(CouponEvaluation.FIRST_ORDER_ONLY);
    }

    private static String code() {
        return "T" + Ids.newId().replace("-", "").substring(20).toUpperCase();
    }

    private static PromotionInput input(String code, Integer totalLimit, Integer perCustomerLimit) {
        return new PromotionInput(code, Map.of("en", "10% off"), "PERCENT", 1000, null, 0, 0, NOW.minusSeconds(60), null,
                totalLimit, perCustomerLimit, false, List.of(), List.of(), List.of(), false, 10000);
    }

    private static CouponRequest request(String code, String customerId) {
        return new CouponRequest(code, customerId, "s1", "b1", "Lahore", 100_000, 15_000, Fulfilment.DELIVERY, PaymentMethod.CASH,
                true, "PKR");
    }
}
