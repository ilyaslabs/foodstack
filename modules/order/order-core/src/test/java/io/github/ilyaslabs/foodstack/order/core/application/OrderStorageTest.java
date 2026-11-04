package io.github.ilyaslabs.foodstack.order.core.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import io.github.ilyaslabs.foodstack.cart.api.CartApi;
import io.github.ilyaslabs.foodstack.cart.api.CheckoutView;
import io.github.ilyaslabs.foodstack.geo.api.DeliveryMode;
import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import io.github.ilyaslabs.foodstack.identity.api.IdentityApi;
import io.github.ilyaslabs.foodstack.identity.api.UserView;
import io.github.ilyaslabs.foodstack.order.api.OrderStatus;
import io.github.ilyaslabs.foodstack.order.core.OrderConfiguration;
import io.github.ilyaslabs.foodstack.order.core.persistence.ActiveOrderDocument;
import io.github.ilyaslabs.foodstack.order.core.persistence.OrderDocument;
import io.github.ilyaslabs.foodstack.payment.api.CardSessionView;
import io.github.ilyaslabs.foodstack.payment.api.PaymentApi;
import io.github.ilyaslabs.foodstack.platform.core.Fulfilment;
import io.github.ilyaslabs.foodstack.platform.core.PaymentMethod;
import io.github.ilyaslabs.foodstack.platform.mongo.FoodstackMongoProperties;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMigrations;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongo;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongoFactory;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.platform.security.FoodstackJwtAuthenticationConverter;
import io.github.ilyaslabs.foodstack.platform.security.PiiCipher;
import io.github.ilyaslabs.foodstack.platform.test.FoodstackContainers;
import io.github.ilyaslabs.foodstack.promotion.api.CouponEvaluation;
import io.github.ilyaslabs.foodstack.promotion.api.PromotionApi;
import io.github.ilyaslabs.foodstack.settings.api.DeliveryView;
import io.github.ilyaslabs.foodstack.settings.api.OrderingView;
import io.github.ilyaslabs.foodstack.settings.api.SettingsApi;
import io.github.ilyaslabs.foodstack.store.api.OrderValidationView;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Placing orders and moving them through their steps against a real two-shard MongoDB (TDD 15.3),
 * with the other modules mocked.
 */
@SpringBootTest(classes = OrderStorageTest.App.class, properties = "spring.data.mongodb.repositories.type=none")
class OrderStorageTest {

    private static final Instant START = Instant.parse("2026-11-01T12:00:00Z");

    private static final String STORE = "s1";

    @SpringBootConfiguration
    @EnableAutoConfiguration(excludeName = {
            "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
            "org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration" })
    static class App {

        @Bean
        ModuleMongo orderMongo(ModuleMongoFactory factory) {
            return factory.create("order");
        }

        @Bean
        ModuleMigrations orderMigrations(ModuleMongo orderMongo, ModuleMongoFactory factory, FoodstackMongoProperties properties) {
            return new ModuleMigrations(orderMongo, factory.connectionString("order"), OrderConfiguration.shardKeys(), properties);
        }
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        FoodstackContainers.registerMongo(registry);
    }

    @Autowired
    ModuleMongo orderMongo;

    private final AtomicReference<Instant> now = new AtomicReference<>(START);

    private final CartApi carts = mock(CartApi.class);

    private final PromotionApi promotions = mock(PromotionApi.class);

    private final PaymentApi payments = mock(PaymentApi.class);

    private final IdentityApi identity = mock(IdentityApi.class);

    private final SettingsApi settings = mock(SettingsApi.class);

    private final OrderLiveUpdates live = mock(OrderLiveUpdates.class);

    private final PiiCipher pii = new PiiCipher(Base64.getEncoder().encodeToString(new byte[32]));

    private OrderLifecycleService lifecycle;

    private OrderPlacementService placement;

    private OrderService orders;

    private OrderProjections projections;

    private String customer;

    @BeforeEach
    void setUp() {
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return OrderStorageTest.this.now.get();
            }
        };
        this.customer = "c-" + System.nanoTime();
        given(this.settings.getOrdering()).willReturn(new OrderingView(true, true, 0, 0, 0, true, 180, 1));
        given(this.settings.getDelivery()).willReturn(new DeliveryView(7000, 0, 30, 5000, 10, 300, 100_000L, 30, 0));
        given(this.identity.getUser(anyString())).willReturn(new UserView(this.customer, "sara@example.com", "Sara", Set.of(),
                null, 0, Set.of(), Set.of()));
        OutboxWriter outbox = new OutboxWriter(this.orderMongo, JsonMapper.builder().build(), clock);
        this.lifecycle = new OrderLifecycleService(this.orderMongo.template(), this.orderMongo.transactionTemplate(), outbox,
                this.live, this.settings, clock);
        this.placement = new OrderPlacementService(this.orderMongo.template(), this.orderMongo.transactionTemplate(), outbox,
                this.carts, this.promotions, this.payments, this.identity, this.lifecycle, this.live, this.pii, null,
                "http://shop/", clock);
        this.orders = new OrderService(this.orderMongo.template(), this.lifecycle, this.pii);
        this.projections = new OrderProjections(this.orderMongo.template());
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a cash pickup order is confirmed at once; a double tap returns it; the store takes it to collected")
    void cashPickup() {
        given(this.carts.checkout(any())).willReturn(checkout(Fulfilment.PICKUP, PaymentMethod.CASH, null));
        PlaceOrder request = pickup(PaymentMethod.CASH, 170_000L);

        PlacedOrder placed = this.placement.place(this.customer, "k1", request);
        assertThat(placed.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(placed.number()).matches("[0-9A-F]{4}-[0-9A-F]{4}");
        assertThat(this.placement.place(this.customer, "k1", request).orderId()).isEqualTo(placed.orderId());
        assertThatThrownBy(() -> this.placement.place(this.customer, "k1", pickup(PaymentMethod.CASH, 1L)))
            .isInstanceOfSatisfying(ModuleException.Conflict.class, (ex) -> assertThat(ex.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        verify(this.carts).clear(this.customer, 7);
        String id = placed.orderId();
        assertThat(this.orderMongo.template().findById(id, ActiveOrderDocument.class).acceptBy()).isEqualTo(START.plusSeconds(180));

        signIn(this.customer);
        OrderDetails mine = this.orders.get(id);
        assertThat(mine.customerName()).isEqualTo("Sara");
        assertThat(mine.contactPhone()).isEqualTo("+92 300 1234567");
        assertThat(mine.actions()).containsExactly("CANCEL");
        assertThatThrownBy(() -> this.orders.storeAction(id, "ACCEPT", 15, null)).isInstanceOf(ModuleException.Forbidden.class);

        signIn("stranger");
        assertThatThrownBy(() -> this.orders.get(id)).isInstanceOf(ModuleException.NotFound.class);
        signIn("owner-2", "other-store");
        assertThatThrownBy(() -> this.orders.board(STORE)).isInstanceOf(ModuleException.Forbidden.class);

        signIn("owner-1", STORE);
        assertThat(this.orders.board(STORE)).extracting(OrderDetails::id).contains(id);
        assertThat(this.orders.get(id).actions()).containsExactly("ACCEPT", "REJECT");
        OrderDetails accepted = this.orders.storeAction(id, "ACCEPT", 25, null);
        assertThat(accepted.readyBy()).isEqualTo(START.plusSeconds(25 * 60).getEpochSecond());
        assertThatThrownBy(() -> this.orders.storeAction(id, "ACCEPT", 25, null))
            .isInstanceOfSatisfying(ModuleException.Conflict.class, (ex) -> assertThat(ex.code()).isEqualTo("ORDER_STATUS_CHANGED"));
        assertThatThrownBy(() -> this.orders.storeAction(id, "PICKED_UP", null, null)).isInstanceOf(ModuleException.Conflict.class);
        this.orders.storeAction(id, "PREPARING", null, null);
        this.orders.storeAction(id, "READY", null, null);
        assertThat(this.orders.get(id).actions()).containsExactly("COLLECTED", "CANCEL");
        OrderDetails done = this.orders.storeAction(id, "COLLECTED", null, null);
        assertThat(done.status()).isEqualTo(OrderStatus.COLLECTED);
        assertThat(done.timeline()).extracting(OrderDetails.Step::status).containsExactly(OrderStatus.PLACED, OrderStatus.CONFIRMED,
                OrderStatus.ACCEPTED, OrderStatus.PREPARING, OrderStatus.READY, OrderStatus.COLLECTED);
        assertThat(this.orderMongo.template().findById(id, ActiveOrderDocument.class)).isNull();
        assertThat(events(id)).containsExactly("OrderPlaced", "OrderConfirmed", "OrderAccepted", "OrderPreparing", "OrderReady",
                "OrderCollected");

        this.projections.refresh(id);
        this.projections.refresh(id);
        assertThat(this.orders.storeHistory(STORE, null, 50).items()).extracting(OrderRow::id).contains(id);
        signIn(this.customer);
        OrderPage history = this.orders.mine(null, 20);
        assertThat(history.items()).singleElement().satisfies((row) -> {
            assertThat(row.status()).isEqualTo(OrderStatus.COLLECTED);
            assertThat(row.itemCount()).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("a card order waits for payment, is confirmed once paid and refunded in full when cancelled")
    void card() {
        given(this.carts.checkout(any())).willReturn(checkout(Fulfilment.PICKUP, PaymentMethod.CARD, null));
        given(this.payments.startCard(any())).willReturn(new CardSessionView("x", "https://pay/1", 0));
        PlacedOrder placed = this.placement.place(this.customer, "k2", pickup(PaymentMethod.CARD, 170_000L));
        String id = placed.orderId();
        assertThat(placed.status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(placed.paymentUrl()).isEqualTo("https://pay/1");
        verify(this.carts, never()).clear(anyString(), any(Long.class));

        signIn("owner-1", STORE);
        assertThat(this.orders.board(STORE)).extracting(OrderDetails::id).doesNotContain(id);

        assertThat(this.lifecycle.paymentSucceeded(id, 170_000)).isNotNull();
        assertThat(this.lifecycle.paymentSucceeded(id, 170_000)).isNull();
        signIn(this.customer);
        OrderDetails paid = this.orders.get(id);
        assertThat(paid.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(paid.paidMinor()).isEqualTo(170_000);
        assertThat(paid.paymentUrl()).isNull();

        OrderDetails cancelled = this.orders.cancel(id, null);
        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.refundRequestedMinor()).isEqualTo(170_000);
        assertThat(lastEvent(id).get("data", String.class)).contains("\"refundMinor\":170000");
        this.lifecycle.refundSucceeded(id, 170_000);
        assertThat(this.orders.get(id).refundedMinor()).isEqualTo(170_000);
        assertThat(events(id)).containsExactly("OrderPlaced", "OrderConfirmed", "OrderCancelled");
    }

    @Test
    @DisplayName("a payment that fails fails the order; one that arrives after cancelling is given back")
    void latePayment() {
        given(this.carts.checkout(any())).willReturn(checkout(Fulfilment.PICKUP, PaymentMethod.CARD, null));
        given(this.payments.startCard(any())).willReturn(new CardSessionView("x", "https://pay/1", 0));
        String failed = this.placement.place(this.customer, "k3", pickup(PaymentMethod.CARD, null)).orderId();
        this.lifecycle.paymentFailed(failed, "DECLINED");
        assertThat(this.lifecycle.require(failed).status()).isEqualTo(OrderStatus.FAILED);
        assertThat(events(failed)).containsExactly("OrderPlaced", "OrderFailed");

        String cancelled = this.placement.place(this.customer, "k4", pickup(PaymentMethod.CARD, null)).orderId();
        signIn(this.customer);
        this.orders.cancel(cancelled, "Changed my mind");
        assertThat(this.lifecycle.paymentSucceeded(cancelled, 170_000)).isNull();
        OrderDocument order = this.lifecycle.require(cancelled);
        assertThat(order.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.refundRequestedMinor()).isEqualTo(170_000);
        assertThat(events(cancelled)).containsExactly("OrderPlaced", "OrderCancelled", "OrderRefunded");
    }

    @Test
    @DisplayName("an order the store does not answer in time is rejected and refunded")
    void acceptTimeout() {
        given(this.carts.checkout(any())).willReturn(checkout(Fulfilment.PICKUP, PaymentMethod.CARD, null));
        given(this.payments.startCard(any())).willReturn(new CardSessionView("x", "https://pay/1", 0));
        String id = this.placement.place(this.customer, "k5", pickup(PaymentMethod.CARD, null)).orderId();
        this.lifecycle.paymentSucceeded(id, 170_000);
        this.now.set(START.plusSeconds(179));
        this.lifecycle.rejectUnanswered();
        assertThat(this.lifecycle.require(id).status()).isEqualTo(OrderStatus.CONFIRMED);
        this.now.set(START.plusSeconds(181));
        int bucket = io.github.ilyaslabs.foodstack.platform.core.Buckets.of(id);
        // a replica holding other buckets leaves it alone (TDD 7.5)
        this.lifecycle.rejectUnanswered(java.util.Set.of((bucket + 1) % 64));
        assertThat(this.lifecycle.require(id).status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(this.lifecycle.rejectUnanswered(java.util.Set.of(bucket))).isGreaterThanOrEqualTo(1);
        OrderDocument order = this.lifecycle.require(id);
        assertThat(order.status()).isEqualTo(OrderStatus.REJECTED);
        assertThat(lastEvent(id).get("data", String.class)).contains("\"automatic\":true").contains("\"refundMinor\":170000");
    }

    @Test
    @DisplayName("support cancels at any step and refunds part of a card payment, never more than paid")
    void support() {
        given(this.carts.checkout(any())).willReturn(checkout(Fulfilment.DELIVERY, PaymentMethod.CARD, null));
        given(this.payments.startCard(any())).willReturn(new CardSessionView("x", "https://pay/1", 0));
        String id = this.placement.place(this.customer, "k6", delivery()).orderId();
        this.lifecycle.paymentSucceeded(id, 170_000);

        signIn("owner-1", STORE);
        this.orders.storeAction(id, "ACCEPT", null, null);
        this.orders.storeAction(id, "READY", null, null);
        assertThatThrownBy(() -> this.orders.storeAction(id, "PICKED_UP", null, null))
            .isInstanceOfSatisfying(ModuleException.Forbidden.class, (ex) -> assertThat(ex.code()).isEqualTo("OPERATOR_DELIVERY"));

        signIn("support-1", null, "order:manage");
        assertThat(this.orders.get(id).actions()).contains("PICKED_UP", "CANCEL", "REFUND");
        assertThatThrownBy(() -> this.orders.refund(id, 170_001, "Cold")).isInstanceOf(ModuleException.BadRequest.class);
        assertThatThrownBy(() -> this.orders.refund(id, 1_000, " ")).isInstanceOf(ModuleException.BadRequest.class);
        assertThat(this.orders.refund(id, 20_000, "Cold food").refundRequestedMinor()).isEqualTo(20_000);
        this.orders.storeAction(id, "PICKED_UP", null, null);
        OrderDetails cancelled = this.orders.cancel(id, "Rider had an accident");
        assertThat(cancelled.refundRequestedMinor()).isEqualTo(170_000);
        assertThat(lastEvent(id).get("data", String.class)).contains("\"refundMinor\":150000");
        assertThat(this.orders.all(null, STORE, null, 5).items()).isNotNull();
    }

    @Test
    @DisplayName("a rider delivery: the code only the customer sees, the rider on the order, picked up and delivered by the rider")
    void riderDelivery() {
        given(this.carts.checkout(any())).willReturn(checkout(Fulfilment.DELIVERY, PaymentMethod.CARD, null));
        given(this.payments.startCard(any())).willReturn(new CardSessionView("x", "https://pay/1", 0));
        String id = this.placement.place(this.customer, "k11", delivery()).orderId();
        this.lifecycle.paymentSucceeded(id, 170_000);
        String cipher = this.lifecycle.require(id).deliveryCodeCipher();
        String code = this.pii.decrypt(cipher);
        assertThat(code).matches("[0-9]{4}");
        String confirmed = lastEvent(id).get("data", String.class);
        assertThat(confirmed).contains("\"deliveryCodeCipher\":\"" + cipher + "\"").doesNotContain("\"" + code + "\"");

        signIn("owner-1", STORE);
        assertThatThrownBy(() -> this.orders.storeAction(id, "ACCEPT", 20, null, "store-rider-1"))
            .isInstanceOfSatisfying(ModuleException.BadRequest.class,
                    (ex) -> assertThat(ex.code()).isEqualTo("STORE_RIDER_NOT_ALLOWED"));
        OrderDetails accepted = this.orders.storeAction(id, "ACCEPT", 20, null);
        assertThat(accepted.deliveryCode()).isNull();
        String data = lastEvent(id).get("data", String.class);
        assertThat(data).contains("\"mode\":\"OPERATOR\"").contains("\"dropoffAddress\":\"12 Canal Road\"")
            .contains("\"cashToCollectMinor\":0")
            // delivery was free (above the threshold), but the rider is paid on the normal charge
            .contains("\"deliveryChargeMinor\":0").contains("\"riderPayBasisMinor\":15000");

        this.lifecycle.riderAssigned(id, "rider-1", "Ali", "MOTORBIKE", "rider:rider-1");
        this.lifecycle.riderAssigned(id, "rider-1", "Ali", "MOTORBIKE", "rider:rider-1");
        signIn(this.customer);
        OrderDetails mine = this.orders.get(id);
        assertThat(mine.rider().name()).isEqualTo("Ali");
        assertThat(mine.deliveryCode()).isEqualTo(code);
        assertThat(mine.timeline()).filteredOn((step) -> step.note() != null && step.note().startsWith("Rider Ali")).hasSize(1);
        this.lifecycle.riderUnassigned(id, "someone-else", "Declined", "system");
        assertThat(this.orders.get(id).rider()).isNotNull();

        this.lifecycle.pickedUp(id, Actor.rider("rider-1"));
        this.lifecycle.delivered(id, Actor.rider("rider-1"));
        OrderDetails done = this.orders.get(id);
        assertThat(done.status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(done.timeline()).extracting(OrderDetails.Step::actorRole).contains("RIDER");
    }

    @Test
    @DisplayName("support hands an operator delivery back: the store then delivers it itself")
    void handedBack() {
        given(this.carts.checkout(any())).willReturn(checkout(Fulfilment.DELIVERY, PaymentMethod.CARD, null));
        given(this.payments.startCard(any())).willReturn(new CardSessionView("x", "https://pay/1", 0));
        String id = this.placement.place(this.customer, "k12", delivery()).orderId();
        this.lifecycle.paymentSucceeded(id, 170_000);
        signIn("owner-1", STORE);
        this.orders.storeAction(id, "ACCEPT", 20, null);
        this.orders.storeAction(id, "READY", null, null);
        this.lifecycle.riderAssigned(id, "rider-1", "Ali", "CAR", "rider:rider-1");

        this.lifecycle.handedBack(id, "No rider nearby", "support:support-1");
        OrderDetails back = this.orders.get(id);
        assertThat(back.deliveryMode()).isEqualTo(DeliveryMode.SELF);
        assertThat(back.handedBackAt()).isEqualTo(START.getEpochSecond());
        assertThat(back.rider()).isNull();
        assertThat(back.actions()).contains("PICKED_UP");
        this.orders.storeAction(id, "PICKED_UP", null, null);
        assertThat(this.orders.storeAction(id, "DELIVERED", null, null).status()).isEqualTo(OrderStatus.DELIVERED);
    }

    @Test
    @DisplayName("nothing is placed when checkout finds a problem, the total changed or the coupon no longer applies")
    void refused() {
        CheckoutView closed = withProblems(checkout(Fulfilment.PICKUP, PaymentMethod.CASH, null));
        given(this.carts.checkout(any())).willReturn(closed);
        assertThatThrownBy(() -> this.placement.place(this.customer, "k7", pickup(PaymentMethod.CASH, null)))
            .isInstanceOfSatisfying(ModuleException.Conflict.class, (ex) -> assertThat(ex.code()).isEqualTo("CHECKOUT_PROBLEMS"));

        given(this.carts.checkout(any())).willReturn(checkout(Fulfilment.PICKUP, PaymentMethod.CASH, null));
        assertThatThrownBy(() -> this.placement.place(this.customer, "k8", pickup(PaymentMethod.CASH, 99L)))
            .isInstanceOfSatisfying(ModuleException.Conflict.class, (ex) -> assertThat(ex.code()).isEqualTo("PRICE_CHANGED"));

        CouponEvaluation coupon = new CouponEvaluation(true, null, Map.of(), "p1", "SAVE10", Map.of("en", "10% off"), 10_000, 0, 0);
        given(this.carts.checkout(any())).willReturn(checkout(Fulfilment.PICKUP, PaymentMethod.CASH, coupon));
        given(this.promotions.reserve(anyString(), any())).willReturn(
                new CouponEvaluation(true, null, Map.of(), "p1", "SAVE10", Map.of("en", "10% off"), 5_000, 0, 0));
        assertThatThrownBy(() -> this.placement.place(this.customer, "k9", pickup(PaymentMethod.CASH, null)))
            .isInstanceOf(ModuleException.Conflict.class);
        verify(this.promotions).release(anyString());
        assertThatThrownBy(() -> this.placement.place(this.customer, " ", pickup(PaymentMethod.CASH, null)))
            .isInstanceOf(ModuleException.BadRequest.class);

        given(this.promotions.reserve(anyString(), any())).willReturn(coupon);
        PlacedOrder placed = this.placement.place(this.customer, "k10", pickup(PaymentMethod.CASH, null));
        assertThat(this.lifecycle.require(placed.orderId()).coupon().code()).isEqualTo("SAVE10");
        assertThat(this.orderMongo.template().count(Query.query(Criteria.where("customerId").is(this.customer)),
                OrderDocument.class)).isEqualTo(1);
    }

    private PlaceOrder pickup(PaymentMethod method, Long expectedTotal) {
        return new PlaceOrder(Fulfilment.PICKUP, null, null, 0, method, expectedTotal, "+92 300 1234567", "en");
    }

    private PlaceOrder delivery() {
        return new PlaceOrder(Fulfilment.DELIVERY, new PlaceOrder.Address(31.5, 74.3, "12 Canal Road", "Gate 2"), null, 0,
                PaymentMethod.CARD, null, null, "ur");
    }

    private CheckoutView checkout(Fulfilment fulfilment, PaymentMethod method, CouponEvaluation coupon) {
        OrderValidationView.StoreSnapshot store = new OrderValidationView.StoreSnapshot(STORE, "spice-route-gulberg", "b1",
                "Spice Route", "Gulberg", Map.of(), new GeoPoint(31.52, 74.35), "1 Main Boulevard", "Lahore",
                fulfilment == Fulfilment.DELIVERY ? DeliveryMode.OPERATOR : DeliveryMode.SELF, true, true, true, 20, 1500, 0,
                Set.of("owner-1"), "Asia/Karachi");
        OrderValidationView.PricedLine line = new OrderValidationView.PricedLine("i1", Map.of("en", "Biryani"), 2, 85_000,
                List.of(new OrderValidationView.PricedOption("g1", Map.of("en", "Size"), "o1", Map.of("en", "Large"), 0)), 170_000);
        long discount = (coupon != null) ? coupon.itemsDiscountMinor() : 0;
        CheckoutView.Delivery delivery = (fulfilment == Fulfilment.DELIVERY)
                ? new CheckoutView.Delivery(true, null, DeliveryMode.OPERATOR, 15_000, 0, 150_000L, 3_000) : null;
        return new CheckoutView(this.customer, 7, store, "PKR", fulfilment, List.of(new CheckoutView.Line("l1", "Less spicy", line)),
                170_000, delivery, coupon, discount, 0, 0, 0, 0, 0, 170_000 - discount, 35, List.of(method), true, List.of());
    }

    private static CheckoutView withProblems(CheckoutView v) {
        return new CheckoutView(v.customerId(), v.cartVersion(), v.store(), v.currency(), v.fulfilment(), v.lines(),
                v.subtotalMinor(), v.delivery(), v.coupon(), v.itemsDiscountMinor(), v.deliveryDiscountMinor(),
                v.deliveryChargeMinor(), v.serviceFeeMinor(), v.taxMinor(), v.tipMinor(), v.totalMinor(), v.etaMinutes(),
                v.paymentMethods(), v.tipsEnabled(), List.of(new CheckoutView.Problem(CheckoutView.STORE_CLOSED, Map.of())));
    }

    private List<String> events(String orderId) {
        return outbox(orderId).stream().map((d) -> d.getString("type").replace("foodstack.order.", "").replace(".v1", "")).toList();
    }

    private Document lastEvent(String orderId) {
        List<Document> all = outbox(orderId);
        return all.get(all.size() - 1);
    }

    private List<Document> outbox(String orderId) {
        return this.orderMongo.template()
            .find(Query.query(Criteria.where("subject").is(orderId)), Document.class, "order_outbox")
            .stream()
            .sorted(java.util.Comparator.comparing((Document d) -> d.getString("_id")))
            .toList();
    }

    private static void signIn(String subject) {
        signIn(subject, null);
    }

    private static void signIn(String subject, String storeId, String... permissions) {
        Jwt.Builder jwt = Jwt.withTokenValue("t").header("alg", "ES256").subject(subject).claim("perms", List.of(permissions));
        if (storeId != null) {
            jwt.claim("storeIds", List.of(storeId)).claim("perms", List.of("store:portal"));
        }
        SecurityContextHolder.getContext().setAuthentication(new FoodstackJwtAuthenticationConverter().convert(jwt.build()));
    }
}
