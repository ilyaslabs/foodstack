package io.github.ilyaslabs.foodstack.payment.core.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import io.github.ilyaslabs.foodstack.payment.api.CardSessionView;
import io.github.ilyaslabs.foodstack.payment.api.PaymentView;
import io.github.ilyaslabs.foodstack.payment.api.StartCardRequest;
import io.github.ilyaslabs.foodstack.payment.core.PaymentConfiguration;
import io.github.ilyaslabs.foodstack.payment.core.gateway.CardGateway.GatewayException;
import io.github.ilyaslabs.foodstack.payment.core.gateway.CardGateway.WebhookEvent;
import io.github.ilyaslabs.foodstack.payment.core.gateway.CardGateway;
import io.github.ilyaslabs.foodstack.payment.core.gateway.SandboxGateway;
import io.github.ilyaslabs.foodstack.payment.core.persistence.PaymentDocument;
import io.github.ilyaslabs.foodstack.platform.core.Ids;
import io.github.ilyaslabs.foodstack.platform.mongo.FoodstackMongoProperties;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMigrations;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongo;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongoFactory;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.platform.test.FoodstackContainers;
import io.github.ilyaslabs.foodstack.settings.api.OrderingView;
import io.github.ilyaslabs.foodstack.settings.api.SettingsApi;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

/**
 * Card payments against a real two-shard MongoDB with the sandbox gateway (the lowest layer, TDD
 * 15.3): starting a page, webhooks applied once, expiry and refunds.
 */
@SpringBootTest(classes = PaymentStorageTest.App.class, properties = "spring.data.mongodb.repositories.type=none")
class PaymentStorageTest {

    private static final Instant START = Instant.parse("2026-11-01T12:00:00Z");

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {

        @Bean
        ModuleMongo paymentMongo(ModuleMongoFactory factory) {
            return factory.create("payment");
        }

        @Bean
        ModuleMigrations paymentMigrations(ModuleMongo paymentMongo, ModuleMongoFactory factory,
                FoodstackMongoProperties properties) {
            return new ModuleMigrations(paymentMongo, factory.connectionString("payment"), PaymentConfiguration.shardKeys(),
                    properties);
        }
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        FoodstackContainers.registerMongo(registry);
    }

    @Autowired
    ModuleMongo paymentMongo;

    private final AtomicReference<Instant> now = new AtomicReference<>(START);

    private final SettingsApi settings = mock(SettingsApi.class);

    private SandboxGateway sandbox;

    private CardGateway gateway;

    private PaymentService payments;

    private SandboxCheckout checkout;

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
                return PaymentStorageTest.this.now.get();
            }
        };
        given(this.settings.getOrdering()).willReturn(new OrderingView(true, true, 0, 0, 0, true, 180, 1));
        JsonMapper json = JsonMapper.builder().build();
        this.sandbox = new SandboxGateway("http://localhost:8080", "secret", json);
        this.gateway = spy(this.sandbox);
        OutboxWriter outbox = new OutboxWriter(this.paymentMongo, json, clock);
        this.payments = new PaymentService(this.paymentMongo.template(), this.paymentMongo.transactionTemplate(), outbox,
                this.settings, this.gateway, Duration.ofMinutes(30), clock);
        this.checkout = new SandboxCheckout(this.sandbox, this.payments, this.paymentMongo.template());
    }

    @Test
    @DisplayName("opens one page per order, pays once however often the webhook arrives, and sends the customer back")
    void pay() {
        String order = Ids.newId();
        CardSessionView session = this.payments.startCard(request(order, 125_000));
        assertThat(session.redirectUrl()).startsWith("http://localhost:8080/api/v1/payments/sandbox/" + order + "?t=");
        assertThat(this.payments.startCard(request(order, 125_000)).redirectUrl()).isEqualTo(session.redirectUrl());
        assertThat(this.payments.get(order).status()).isEqualTo(PaymentDocument.PENDING);
        assertThat(this.payments.customerOf(order)).isEqualTo("c1");

        String token = this.sandbox.pageToken(order);
        assertThat(this.checkout.page(order, token).amountMinor()).isEqualTo(125_000);
        assertThatThrownBy(() -> this.checkout.page(order, "wrong")).isInstanceOf(ModuleException.NotFound.class);
        assertThat(this.checkout.complete(order, token, true)).isEqualTo("https://shop/orders/" + order);

        PaymentDocument stored = this.paymentMongo.template().findById(order, PaymentDocument.class);
        Map.Entry<byte[], Map<String, String>> again = this.sandbox.webhook(WebhookEvent.SUCCEEDED, order,
                stored.sessionReference(), 125_000);
        this.payments.handleWebhook(SandboxGateway.ID, again.getKey(), again.getValue());

        PaymentView paid = this.payments.get(order);
        assertThat(paid.status()).isEqualTo(PaymentDocument.SUCCEEDED);
        assertThat(paid.redirectUrl()).isNull();
        assertThat(events(order)).containsExactly("foodstack.payment.PaymentSucceeded.v1");
        assertThatThrownBy(() -> this.payments.startCard(request(order, 125_000))).isInstanceOf(ModuleException.Conflict.class);
    }

    @Test
    @DisplayName("refuses webhooks with a bad signature, for another gateway, or for another page")
    void webhookChecks() {
        String order = Ids.newId();
        this.payments.startCard(request(order, 1_000));
        Map.Entry<byte[], Map<String, String>> other = this.sandbox.webhook(WebhookEvent.SUCCEEDED, order, "sbx_other", 1_000);

        assertThatThrownBy(() -> this.payments.handleWebhook(SandboxGateway.ID, other.getKey(), Map.of()))
            .isInstanceOf(ModuleException.BadRequest.class);
        assertThatThrownBy(() -> this.payments.handleWebhook("stripe", other.getKey(), other.getValue()))
            .isInstanceOf(ModuleException.NotFound.class);
        this.payments.handleWebhook(SandboxGateway.ID, other.getKey(), other.getValue());
        assertThat(this.payments.get(order).status()).isEqualTo(PaymentDocument.PENDING);
        assertThat(events(order)).isEmpty();
    }

    @Test
    @DisplayName("a declined card fails the payment once; the customer is sent back to try again")
    void decline() {
        String order = Ids.newId();
        this.payments.startCard(request(order, 1_000));
        assertThat(this.checkout.complete(order, this.sandbox.pageToken(order), false)).isEqualTo("https://shop/checkout");
        assertThat(this.payments.get(order).status()).isEqualTo(PaymentDocument.FAILED);
        assertThat(events(order)).containsExactly("foodstack.payment.PaymentFailed.v1");
    }

    @Test
    @DisplayName("pages nobody finishes expire after a grace period; a late payment still counts")
    void expiry() {
        String order = Ids.newId();
        this.payments.startCard(request(order, 1_000));
        this.now.set(START.plus(Duration.ofMinutes(34)));
        assertThat(this.payments.expireStale()).isZero();
        this.now.set(START.plus(Duration.ofMinutes(36)));
        assertThat(this.payments.expireStale()).isEqualTo(1);
        assertThat(this.payments.expireStale()).isZero();
        assertThat(this.payments.get(order).status()).isEqualTo(PaymentDocument.EXPIRED);

        this.checkout.complete(order, this.sandbox.pageToken(order), true);
        assertThat(this.payments.get(order).status()).isEqualTo(PaymentDocument.SUCCEEDED);
        assertThat(events(order)).containsExactly("foodstack.payment.PaymentFailed.v1", "foodstack.payment.PaymentSucceeded.v1");
    }

    @Test
    @DisplayName("refunds at most what was paid, once per refund id; a gateway outage is retried, a refusal recorded")
    void refunds() {
        String order = paidOrder(10_000);

        this.payments.refund(order, 4_000, "r1", "Order cancelled");
        this.payments.refund(order, 4_000, "r1", "Order cancelled");
        assertThat(this.payments.get(order).refundedMinor()).isEqualTo(4_000);

        willThrow(new GatewayException("down", true, null)).given(this.gateway).refund(anyString(), anyLong(), anyString(), anyString());
        assertThatThrownBy(() -> this.payments.refund(order, 9_000, "r2", "Support")).isInstanceOf(GatewayException.class);
        assertThat(this.payments.get(order).refundedMinor()).isEqualTo(4_000);

        willThrow(new GatewayException("refused", false, null)).given(this.gateway).refund(anyString(), anyLong(), anyString(), anyString());
        this.payments.refund(order, 9_000, "r3", "Support");
        assertThat(this.payments.get(order).refundedMinor()).isEqualTo(4_000);

        org.mockito.Mockito.reset(this.gateway);
        this.payments.refund(order, 9_000, "r2", "Support");
        PaymentDocument stored = this.paymentMongo.template().findById(order, PaymentDocument.class);
        assertThat(stored.refundedMinor()).isEqualTo(10_000);
        assertThat(stored.refunds()).extracting(PaymentDocument.Refund::id, PaymentDocument.Refund::amountMinor,
                PaymentDocument.Refund::status)
            .containsExactly(org.assertj.core.groups.Tuple.tuple("r1", 4_000L, "SUCCEEDED"),
                    org.assertj.core.groups.Tuple.tuple("r3", 6_000L, "FAILED"),
                    org.assertj.core.groups.Tuple.tuple("r2", 6_000L, "SUCCEEDED"));
        this.payments.refund(order, 1, "r4", "Support");
        assertThat(events(order)).containsExactly("foodstack.payment.PaymentSucceeded.v1",
                "foodstack.payment.RefundSucceeded.v1", "foodstack.payment.RefundFailed.v1",
                "foodstack.payment.RefundSucceeded.v1");
    }

    @Test
    @DisplayName("card needs the setting and a gateway; nothing is opened for a zero amount")
    void availability() {
        given(this.settings.getOrdering()).willReturn(new OrderingView(true, false, 0, 0, 0, true, 180, 2));
        assertThat(this.payments.options().card()).isFalse();
        assertThatThrownBy(() -> this.payments.startCard(request(Ids.newId(), 1_000))).isInstanceOf(ModuleException.BadRequest.class);

        given(this.settings.getOrdering()).willReturn(new OrderingView(true, true, 0, 0, 0, true, 180, 3));
        assertThat(this.payments.options().gateway()).isEqualTo("sandbox");
        assertThatThrownBy(() -> this.payments.startCard(request(Ids.newId(), 0))).isInstanceOf(ModuleException.BadRequest.class);

        willThrow(new GatewayException("down", true, null)).given(this.gateway).createSession(any());
        String order = Ids.newId();
        assertThatThrownBy(() -> this.payments.startCard(request(order, 1_000))).isInstanceOf(ModuleException.Unavailable.class);
        org.mockito.Mockito.reset(this.gateway);
        assertThat(this.payments.startCard(request(order, 1_000)).redirectUrl()).isNotNull();
    }

    private String paidOrder(long amount) {
        String order = Ids.newId();
        this.payments.startCard(request(order, amount));
        this.checkout.complete(order, this.sandbox.pageToken(order), true);
        return order;
    }

    private List<String> events(String order) {
        return this.paymentMongo.template()
            .find(Query.query(Criteria.where("subject").is(order)), Document.class, "payment_outbox")
            .stream()
            .sorted(java.util.Comparator.comparing((Document d) -> String.valueOf(d.get("_id"))))
            .map((d) -> d.getString("type"))
            .toList();
    }

    private static StartCardRequest request(String order, long amount) {
        return new StartCardRequest(order, "A-1", "c1", amount, "PKR", "Spice Route", "https://shop/orders/" + order,
                "https://shop/checkout");
    }
}
