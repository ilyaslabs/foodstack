package io.github.ilyaslabs.foodstack.payment.core.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.ilyaslabs.foodstack.payment.api.MerchantView;
import io.github.ilyaslabs.foodstack.payment.api.StartCardRequest;
import io.github.ilyaslabs.foodstack.payment.core.PaymentConfiguration;
import io.github.ilyaslabs.foodstack.payment.core.gateway.CardGateway;
import io.github.ilyaslabs.foodstack.payment.core.gateway.CardGateway.WebhookEvent;
import io.github.ilyaslabs.foodstack.payment.core.gateway.SandboxGateway;
import io.github.ilyaslabs.foodstack.payment.core.persistence.MerchantAccountDocument;
import io.github.ilyaslabs.foodstack.payment.core.persistence.PaymentDocument;
import io.github.ilyaslabs.foodstack.platform.core.Ids;
import io.github.ilyaslabs.foodstack.platform.mongo.FoodstackMongoProperties;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMigrations;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongo;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongoFactory;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.platform.security.BranchBrands;
import io.github.ilyaslabs.foodstack.platform.security.CurrentUser;
import io.github.ilyaslabs.foodstack.platform.security.FoodstackJwtAuthenticationConverter;
import io.github.ilyaslabs.foodstack.platform.security.PiiCipher;
import io.github.ilyaslabs.foodstack.platform.test.FoodstackContainers;
import io.github.ilyaslabs.foodstack.settings.api.OrderingView;
import io.github.ilyaslabs.foodstack.settings.api.SettingsApi;
import io.github.ilyaslabs.moduleswitch.ModuleException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Brand and branch merchant accounts against a real two-shard MongoDB with the sandbox gateway (the
 * lowest layer, TDD 15.3): connecting with a test call, which account a branch's payment goes into,
 * suspension over the credit limit, and payments, webhooks and refunds on the store's account.
 */
@SpringBootTest(classes = MerchantAccountsStorageTest.App.class, properties = "spring.data.mongodb.repositories.type=none")
class MerchantAccountsStorageTest {

    private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");

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

    private final SettingsApi settings = mock(SettingsApi.class);

    private final BranchBrands branchBrands = mock(BranchBrands.class);

    private final PiiCipher cipher = new PiiCipher("aW50ZWdyYXRpb24tdGVzdHMtcGlpLWtleS1ub3Qtc2VjcmV0");

    private SandboxGateway sandbox;

    private MerchantAccounts accounts;

    private PaymentService payments;

    private SandboxCheckout checkout;

    private String brand;

    private String store;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        this.brand = Ids.newId();
        this.store = Ids.newId();
        directCollection(true);
        given(this.branchBrands.brandOf(this.store)).willReturn(Optional.of(this.brand));
        CurrentUser.useBranchBrands(this.branchBrands);
        JsonMapper json = JsonMapper.builder().build();
        this.sandbox = new SandboxGateway("http://localhost:8080", "secret", json);
        CardGateway.AccountGateways opener = (key, hook) -> this.sandbox.forAccount(key);
        this.accounts = new MerchantAccounts(this.paymentMongo.template(), this.paymentMongo.transactionTemplate(),
                this.settings, this.sandbox, opener, this.cipher, "https://api.shop", clock);
        this.payments = new PaymentService(this.paymentMongo.template(), this.paymentMongo.transactionTemplate(),
                new OutboxWriter(this.paymentMongo, json, clock), this.settings, this.sandbox, Duration.ofMinutes(30), clock,
                this.accounts);
        this.checkout = new SandboxCheckout(this.sandbox, this.payments, this.paymentMongo.template());
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
        CurrentUser.useBranchBrands(null);
    }

    @Test
    @DisplayName("an owner connects only once finance allows the branch, and only with keys the gateway accepts")
    void connect() {
        signIn("owner-1", List.of(this.brand), List.of());
        assertThatThrownBy(() -> this.accounts.connect("BRAND", this.brand, "sbx_live_1234", "whsec_12345678"))
            .isInstanceOfSatisfying(ModuleException.class, (ex) -> assertThat(ex.code()).isEqualTo("DIRECT_COLLECTION_OFF"));

        this.accounts.collectionChanged(this.store, this.brand, "DIRECT");
        assertThatThrownBy(() -> this.accounts.connect("BRAND", this.brand, "sk_wrong_1234", "whsec_12345678"))
            .isInstanceOfSatisfying(ModuleException.class,
                    (ex) -> assertThat(ex.code()).isEqualTo("MERCHANT_ACCOUNT_REFUSED"));
        MerchantAccounts.AccountView first = this.accounts.connect("BRAND", this.brand, "sbx_live_1234", "whsec_12345678");
        assertThat(first.status()).isEqualTo(MerchantAccountDocument.ACTIVE);
        assertThat(first.keyHint()).isEqualTo("1234");

        MerchantAccountDocument stored = this.paymentMongo.template().findById(first.id(), MerchantAccountDocument.class);
        assertThat(stored.secretKey()).doesNotContain("sbx_live").isNotBlank();
        assertThat(this.cipher.decrypt(stored.secretKey())).isEqualTo("sbx_live_1234");

        MerchantAccounts.AccountView second = this.accounts.connect("BRAND", this.brand, "sbx_live_5678", "whsec_12345678");
        assertThat(this.paymentMongo.template().findById(first.id(), MerchantAccountDocument.class).status())
            .isEqualTo(MerchantAccountDocument.REVOKED);
        assertThat(this.accounts.view(this.store).brandAccount().id()).isEqualTo(second.id());

        signIn("owner-2", List.of(), List.of(this.store));
        assertThatThrownBy(() -> this.accounts.connect("BRAND", this.brand, "sbx_live_1234", "whsec_12345678"))
            .isInstanceOf(ModuleException.Forbidden.class);
        assertThat(this.accounts.connect("STORE", this.store, "sbx_branch_99", "whsec_12345678").scope()).isEqualTo("STORE");
    }

    @Test
    @DisplayName("payments go into the branch's own account, else the brand's, only while allowed and within the limit")
    void resolve() {
        assertThat(this.accounts.resolve(this.store)).isEqualTo(MerchantView.BY_OPERATOR);
        this.accounts.collectionChanged(this.store, this.brand, "DIRECT");
        assertThat(this.accounts.resolve(this.store)).isEqualTo(MerchantView.BY_OPERATOR);

        signIn("owner-1", List.of(this.brand), List.of(this.store));
        String brandAccount = this.accounts.connect("BRAND", this.brand, "sbx_live_1234", "whsec_12345678").id();
        assertThat(this.accounts.resolve(this.store)).isEqualTo(new MerchantView(brandAccount, MerchantView.STORE));
        String own = this.accounts.connect("STORE", this.store, "sbx_branch_99", "whsec_12345678").id();
        assertThat(this.accounts.resolve(this.store).accountId()).isEqualTo(own);

        this.accounts.suspended(this.store, this.brand, true);
        assertThat(this.accounts.resolve(this.store)).isEqualTo(MerchantView.BY_OPERATOR);
        this.accounts.suspended(this.store, this.brand, false);
        assertThat(this.accounts.resolve(this.store).accountId()).isEqualTo(own);

        directCollection(false);
        assertThat(this.accounts.resolve(this.store)).isEqualTo(MerchantView.BY_OPERATOR);
        directCollection(true);
        this.accounts.markFailed(own, "Stripe: invalid API key");
        assertThat(this.accounts.resolve(this.store).accountId()).isEqualTo(brandAccount);
        this.accounts.disconnect("BRAND", this.brand);
        this.accounts.collectionChanged(this.store, this.brand, "OPERATOR");
        assertThat(this.accounts.resolve(this.store)).isEqualTo(MerchantView.BY_OPERATOR);
    }

    @Test
    @DisplayName("a payment on a branch's account is paid, matched to webhooks at the owner's address and refunded there")
    void payOnStoreAccount() {
        this.accounts.collectionChanged(this.store, this.brand, "DIRECT");
        signIn("owner-1", List.of(), List.of(this.store));
        String account = this.accounts.connect("STORE", this.store, "sbx_branch_99", "whsec_12345678").id();
        String order = Ids.newId();
        this.payments.startCard(new StartCardRequest(order, "A-1", "c1", 9_000, "PKR", "Napoli", "https://shop/orders/" + order,
                "https://shop/checkout", account));
        assertThat(this.paymentMongo.template().findById(order, PaymentDocument.class).merchantAccount()).isEqualTo(account);

        PaymentDocument stored = this.paymentMongo.template().findById(order, PaymentDocument.class);
        Map.Entry<byte[], Map<String, String>> hook = this.sandbox.webhook(WebhookEvent.SUCCEEDED, order,
                stored.sessionReference(), 9_000);
        this.payments.handleWebhook(SandboxGateway.ID, hook.getKey(), hook.getValue());
        assertThat(this.payments.get(order).status()).isEqualTo(PaymentDocument.PENDING);

        this.payments.handleOwnerWebhook(SandboxGateway.ID, "STORE", this.store, hook.getKey(), hook.getValue());
        assertThat(this.payments.get(order).status()).isEqualTo(PaymentDocument.SUCCEEDED);

        this.payments.refund(order, 2_000, "r1", "cold");
        assertThat(this.payments.get(order).refundedMinor()).isEqualTo(2_000);
        assertThat(events(order)).contains("foodstack.payment.PaymentSucceeded.v1", "foodstack.payment.RefundSucceeded.v1");

        String other = Ids.newId();
        this.payments.startCard(new StartCardRequest(other, "A-2", "c1", 1_000, "PKR", "Napoli", "r", "c", account));
        assertThat(this.checkout.complete(other, this.sandbox.pageToken(other), true)).isEqualTo("r");
        assertThat(this.payments.get(other).status()).isEqualTo(PaymentDocument.SUCCEEDED);
    }

    @Test
    @DisplayName("owners and finance see where the branch's card payments go; others do not")
    void view() {
        this.accounts.collectionChanged(this.store, this.brand, "DIRECT");
        signIn("finance-1", List.of(), List.of(), "payment:manage");
        MerchantAccounts.CollectionView view = this.accounts.view(this.store);
        assertThat(view.mode()).isEqualTo("DIRECT");
        assertThat(view.collectedBy()).isEqualTo(MerchantView.OPERATOR);
        assertThat(view.storeWebhookUrl()).isEqualTo("https://api.shop/api/v1/payment-webhooks/sandbox/stores/" + this.store);
        assertThat(view.canConnectStore()).isFalse();

        signIn("owner-1", List.of(this.brand), List.of());
        assertThat(this.accounts.view(this.store).canConnectBrand()).isTrue();
        signIn("someone", List.of(), List.of("other-store"));
        assertThatThrownBy(() -> this.accounts.view(this.store)).isInstanceOf(ModuleException.Forbidden.class);
    }

    private void directCollection(boolean on) {
        given(this.settings.getOrdering())
            .willReturn(new OrderingView(true, true, 0, 0, 0, true, 180, 1, false, on, 5_000_000L));
    }

    private List<String> events(String orderId) {
        return this.paymentMongo.template()
            .find(Query.query(Criteria.where("subject").is(orderId)), org.bson.Document.class, "payment_outbox")
            .stream()
            .map((d) -> d.getString("type"))
            .toList();
    }

    private static void signIn(String subject, List<String> brandIds, List<String> storeIds, String... extra) {
        List<String> perms = (extra.length > 0) ? List.of(extra) : List.of("store:portal", MerchantAccounts.PAYMENT_ACCOUNT);
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "ES256").subject(subject).claim("perms", perms)
            .claim("brandIds", brandIds).claim("storeIds", storeIds).build();
        SecurityContextHolder.getContext().setAuthentication(new FoodstackJwtAuthenticationConverter().convert(jwt));
    }
}
