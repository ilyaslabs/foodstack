package io.github.ilyaslabs.foodstack.ledger.core.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import io.github.ilyaslabs.foodstack.dispatch.api.DeliveryStatus;
import io.github.ilyaslabs.foodstack.dispatch.api.DeliverySummary;
import io.github.ilyaslabs.foodstack.dispatch.api.events.DeliveryCompleted;
import io.github.ilyaslabs.foodstack.geo.api.DeliveryMode;
import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import io.github.ilyaslabs.foodstack.ledger.core.LedgerConfiguration;
import io.github.ilyaslabs.foodstack.ledger.core.domain.Accounts;
import io.github.ilyaslabs.foodstack.ledger.core.domain.Kinds;
import io.github.ilyaslabs.foodstack.ledger.core.persistence.CreditDocument;
import io.github.ilyaslabs.foodstack.ledger.core.persistence.TransactionDocument;
import io.github.ilyaslabs.foodstack.order.api.OrderMoney;
import io.github.ilyaslabs.foodstack.order.api.OrderStatus;
import io.github.ilyaslabs.foodstack.order.api.OrderSummary;
import io.github.ilyaslabs.foodstack.order.api.events.OrderRefunded;
import io.github.ilyaslabs.foodstack.payment.api.events.PaymentSucceeded;
import io.github.ilyaslabs.foodstack.payment.api.events.RefundSucceeded;
import io.github.ilyaslabs.foodstack.platform.core.Fulfilment;
import io.github.ilyaslabs.foodstack.platform.core.PaymentMethod;
import io.github.ilyaslabs.foodstack.platform.mongo.FoodstackMongoProperties;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMigrations;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongo;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongoFactory;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.platform.security.FoodstackJwtAuthenticationConverter;
import io.github.ilyaslabs.foodstack.platform.test.FoodstackContainers;
import io.github.ilyaslabs.foodstack.settings.api.BrandingView;
import io.github.ilyaslabs.foodstack.settings.api.LocaleView;
import io.github.ilyaslabs.foodstack.settings.api.OrderingView;
import io.github.ilyaslabs.foodstack.settings.api.SettingsApi;
import io.github.ilyaslabs.moduleswitch.ModuleException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.bson.Document;
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
 * Postings, roll-ups, reports and settlement against a real two-shard MongoDB (TDD 15.3), with
 * settings mocked. Each test uses its own branches and riders, so they never see each other's lines.
 */
@SpringBootTest(classes = LedgerStorageTest.App.class, properties = "spring.data.mongodb.repositories.type=none")
class LedgerStorageTest {

    /** 10:00 in Karachi on 5 November. */
    private static final Instant START = Instant.parse("2026-11-05T05:00:00Z");

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {

        @Bean
        ModuleMongo ledgerMongo(ModuleMongoFactory factory) {
            return factory.create("ledger");
        }

        @Bean
        ModuleMigrations ledgerMigrations(ModuleMongo ledgerMongo, ModuleMongoFactory factory,
                FoodstackMongoProperties properties) {
            return new ModuleMigrations(ledgerMongo, factory.connectionString("ledger"), LedgerConfiguration.shardKeys(),
                    properties);
        }
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        FoodstackContainers.registerMongo(registry);
    }

    @Autowired
    ModuleMongo ledgerMongo;

    private final AtomicReference<Instant> now = new AtomicReference<>(START);

    private final SettingsApi settings = mock(SettingsApi.class);

    private LedgerPosting posting;

    private Rollups rollups;

    private LedgerReports reports;

    private OutboxWriter outbox;

    private String s1;

    private String s2;

    private String s3;

    private String b1;

    private String b2;

    private String r1;

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
                return LedgerStorageTest.this.now.get();
            }
        };
        given(this.settings.getLocale()).willReturn(new LocaleView("en", List.of("en"), "PKR", "Asia/Karachi", 1));
        given(this.settings.getBranding()).willReturn(new BrandingView("Lahore Eats", null, null, null, null, null, null, 1, null));
        this.outbox = new OutboxWriter(this.ledgerMongo, JsonMapper.builder().build(), clock);
        this.posting = new LedgerPosting(this.ledgerMongo.template(), this.settings, clock);
        this.rollups = new Rollups(this.ledgerMongo.template(), this.ledgerMongo.transactionTemplate());
        this.reports = new LedgerReports(this.ledgerMongo.template(), this.ledgerMongo.transactionTemplate(), this.outbox,
                this.posting, this.settings, clock);
        String run = UUID.randomUUID().toString().substring(0, 8);
        this.s1 = "s1-" + run;
        this.s2 = "s2-" + run;
        this.s3 = "s3-" + run;
        this.b1 = "b1-" + run;
        this.b2 = "b2-" + run;
        this.r1 = "r1-" + run;
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a day of orders balances, rolls up, reports netted payouts both ways, and settles once with a reference")
    void dayOfOrders() throws Exception {
        // 1. card, the branch's own delivery: the operator owes the branch
        String o1 = "o1-" + this.s1;
        OrderSummary card = summary(o1, this.s1, this.b1, OrderStatus.DELIVERED, PaymentMethod.CARD, DeliveryMode.SELF);
        this.posting.orderPlaced(card);
        this.posting.paymentReceived(new PaymentSucceeded(o1, card.money().totalMinor(), "PKR", "sandbox", "ref-1"), START);
        this.posting.orderDone(card, START);
        this.posting.orderDone(card, START);

        // 2. cash, an operator rider: the rider carries the cash, the branch is owed its share
        String o2 = "o2-" + this.s2;
        OrderSummary cash = summary(o2, this.s2, this.b1, OrderStatus.DELIVERED, PaymentMethod.CASH, DeliveryMode.OPERATOR);
        this.posting.orderPlaced(cash);
        this.posting.orderDone(cash, START);
        this.posting.deliveryCompleted(delivered(o2, this.s2, this.r1, cash.money().totalMinor()), START);

        // 3. a refund asked while preparing waits for delivery, and the branch bears it
        String o3 = "o3-" + this.s1;
        OrderSummary preparing = summary(o3, this.s1, this.b1, OrderStatus.PREPARING, PaymentMethod.CARD, DeliveryMode.SELF);
        this.posting.orderPlaced(preparing);
        this.posting.paymentReceived(new PaymentSucceeded(o3, preparing.money().totalMinor(), "PKR", "sandbox", "ref-3"),
                START);
        OrderRefunded early = new OrderRefunded(preparing, "user:support", 5_000, "Missing drink", OrderRefunded.STORE);
        this.posting.orderRefunded("evt-3", early, START);
        this.posting.orderRefunded("evt-3", early, START);
        assertThat(count(o3, LedgerPosting.REFUND)).isZero();
        this.posting.orderDone(summary(o3, this.s1, this.b1, OrderStatus.DELIVERED, PaymentMethod.CARD, DeliveryMode.SELF),
                START);
        assertThat(count(o3, LedgerPosting.REFUND)).isEqualTo(1);

        // 4. cancelled after a refund was asked: nothing earned, the waiting refund is never posted
        String o4 = "o4-" + this.s3;
        OrderSummary cancelled = summary(o4, this.s3, this.b2, OrderStatus.PREPARING, PaymentMethod.CARD, DeliveryMode.SELF);
        this.posting.orderPlaced(cancelled);
        this.posting.paymentReceived(new PaymentSucceeded(o4, 1_000, "PKR", "sandbox", "ref-4"), START);
        this.posting.orderRefunded("evt-4", new OrderRefunded(cancelled, "user:support", 1_000, "x", OrderRefunded.OPERATOR),
                START);
        this.posting.orderClosed(o4);
        this.posting.refundPaidOut(new RefundSucceeded(o4, "rf-4", 1_000, 1_000, "PKR"), START);

        // every transaction balances, and the money paid for orders not done is back to zero for these orders
        for (TransactionDocument t : transactions()) {
            assertThat(t.legs().stream().mapToLong((l) -> l.amountMinor()).sum()).as(t.id()).isZero();
        }

        assertThat(this.rollups.rollUp(null)).isGreaterThan(0);
        assertThat(this.rollups.rollUp(null)).isZero();

        signIn("finance-1", null, LedgerReports.LEDGER_READ, LedgerReports.LEDGER_SETTLE);
        PayoutReport stores = this.reports.payouts("STORE", "2026-11-01", "2026-11-05", this.b1);
        assertThat(stores.rows()).extracting(PayoutRow::partyId).containsExactlyInAnyOrder(this.s1, this.s2);
        long cardShare = 100_000 + 5_000 + 15_000 + 3_000 - 10_500;
        PayoutRow first = row(stores, this.s1);
        assertThat(first.dueMinor()).isEqualTo(2 * cardShare - 5_000);
        assertThat(first.direction()).isEqualTo(PayoutRow.OPERATOR_PAYS);
        assertThat(first.kinds()).containsEntry(Kinds.FOOD, 200_000L).containsEntry(Kinds.REFUND, -5_000L)
            .containsEntry(Kinds.COMMISSION, -21_000L);
        assertThat(first.name()).isEqualTo("Napoli " + this.s1);
        PayoutRow second = row(stores, this.s2);
        assertThat(second.dueMinor()).isEqualTo(100_000 + 5_000 - 10_500);
        assertThat(stores.totalDueMinor()).isEqualTo(first.dueMinor() + second.dueMinor());

        PayoutReport riders = this.reports.payouts("RIDER", "2026-11-05", "2026-11-05", null);
        PayoutRow rider = riders.rows().stream().filter((r) -> r.partyId().equals(this.r1)).findFirst().orElseThrow();
        assertThat(rider.dueMinor()).isEqualTo(10_500 + 3_000 - cash.money().totalMinor());
        assertThat(rider.direction()).isEqualTo(PayoutRow.PARTY_PAYS);
        assertThat(rider.name()).isEqualTo("Hamza");

        // a branch's balance with the operator, for the credit limit
        assertThat(this.reports.balance(Accounts.store(this.s1))).isEqualTo(first.dueMinor());

        // CSV and PDF carry the same numbers
        String csv = this.reports.payoutsCsv("STORE", "2026-11-01", "2026-11-05", this.b1);
        assertThat(csv).contains("Napoli " + this.s1).contains(PayoutExports.major(first.dueMinor(), "PKR"))
            .contains("Operator pays branch");
        byte[] pdf = this.reports.payoutsPdf("STORE", "2026-11-01", "2026-11-05", this.b1);
        try (var document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("Branch payouts").contains("Lahore Eats").contains("Napoli " + this.s1)
                .contains(PayoutExports.major(first.dueMinor(), "PKR"));
        }

        // settling: only a day that is over, once, with a reference
        assertThatThrownBy(() -> this.reports.settle(Accounts.store(this.s1), "2026-11-05", "TRX-1"))
            .isInstanceOfSatisfying(ModuleException.BadRequest.class,
                    (ex) -> assertThat(ex.code()).isEqualTo("SETTLEMENT_PERIOD_OPEN"));
        this.now.set(Instant.parse("2026-11-06T06:00:00Z"));
        assertThatThrownBy(() -> this.reports.settle(Accounts.store(this.s1), "2026-11-05", " "))
            .isInstanceOf(ModuleException.BadRequest.class);
        SettlementView settled = this.reports.settle(Accounts.store(this.s1), "2026-11-05", "TRX-1");
        assertThat(settled.amountMinor()).isEqualTo(first.dueMinor());
        assertThat(settled.name()).isEqualTo("Napoli " + this.s1);
        assertThatThrownBy(() -> this.reports.settle(Accounts.store(this.s1), "2026-11-05", "TRX-2"))
            .isInstanceOfSatisfying(ModuleException.Conflict.class, (ex) -> assertThat(ex.code()).isEqualTo("NOTHING_TO_SETTLE"));
        assertThatThrownBy(() -> this.reports.settle(Accounts.store(this.s1), "2026-11-04", "TRX-2"))
            .isInstanceOfSatisfying(ModuleException.Conflict.class,
                    (ex) -> assertThat(ex.code()).isEqualTo("SETTLEMENT_PERIOD_SETTLED"));
        assertThat(events()).anyMatch((e) -> e.getString("type").endsWith("PayoutSettled.v1"))
            .anyMatch((e) -> e.getString("type").endsWith("StoreBalanceSettled.v1"));

        // the report now shows the period settled, before and after the settlement is rolled up
        assertThat(row(this.reports.payouts("STORE", "2026-11-01", "2026-11-05", this.b1), this.s1).direction())
            .isEqualTo(PayoutRow.SETTLED);
        this.rollups.rollUp(null);
        PayoutRow after = row(this.reports.payouts("STORE", "2026-11-01", "2026-11-05", this.b1), this.s1);
        assertThat(after.dueMinor()).isZero();
        assertThat(after.settledAfterMinor()).isEqualTo(-first.dueMinor());
        assertThat(this.reports.balance(Accounts.store(this.s1))).isZero();
        assertThat(this.reports.settlements(Accounts.store(this.s1), 10)).extracting(SettlementView::reference)
            .containsExactly("TRX-1");

        // the statement shows each transaction's lines for the account, newest first, in pages
        StatementPage page = this.reports.statement(Accounts.store(this.s1), null, null, null, 2);
        assertThat(page.lines()).hasSize(2);
        assertThat(page.lines().get(0).type()).isEqualTo(LedgerPosting.SETTLEMENT);
        assertThat(page.nextBefore()).isNotNull();
        assertThat(this.reports.statement(Accounts.store(this.s1), null, null, page.nextBefore(), 10).lines()).hasSize(2);

        // the operator's earnings: commission, delivery and service fees, less rider pay
        OperatorSummary operator = this.reports.operator("2026-11-05", "2026-11-05");
        assertThat(operator.accounts().get(Accounts.COMMISSION)).isGreaterThanOrEqualTo(31_500L);
        assertThat(operator.accounts()).containsKey(Accounts.RIDER_PAY).doesNotContainKey(Accounts.BANK);
    }

    @Test
    @DisplayName("owners see their own branches only; staff and other brands are refused")
    void access() {
        OrderSummary card = summary("oa-" + this.s1, this.s1, this.b1, OrderStatus.DELIVERED, PaymentMethod.CARD,
                DeliveryMode.SELF);
        this.posting.orderDone(card, START);
        OrderSummary other = summary("ob-" + this.s3, this.s3, this.b2, OrderStatus.DELIVERED, PaymentMethod.CARD,
                DeliveryMode.SELF);
        this.posting.orderDone(other, START);

        signInOwner(this.b1, null);
        assertThat(this.reports.payouts("STORE", "2026-11-05", "2026-11-05", this.b1).rows()).extracting(PayoutRow::partyId)
            .containsExactly(this.s1);
        assertThat(this.reports.payouts("STORE", "2026-11-05", "2026-11-05", null).rows()).extracting(PayoutRow::partyId)
            .containsExactly(this.s1);
        assertThatThrownBy(() -> this.reports.payouts("STORE", "2026-11-05", "2026-11-05", this.b2))
            .isInstanceOf(ModuleException.Forbidden.class);
        assertThatThrownBy(() -> this.reports.payouts("RIDER", "2026-11-05", "2026-11-05", null))
            .isInstanceOf(ModuleException.Forbidden.class);
        assertThatThrownBy(() -> this.reports.operator("2026-11-05", "2026-11-05")).isInstanceOf(ModuleException.Forbidden.class);
        assertThatThrownBy(() -> this.reports.settle(Accounts.store(this.s1), "2026-11-04", "x"))
            .isInstanceOf(ModuleException.Forbidden.class);

        signInOwner(null, this.s1);
        assertThat(this.reports.payouts("STORE", "2026-11-05", "2026-11-05", null).rows()).extracting(PayoutRow::partyId)
            .containsExactly(this.s1);
        assertThat(this.reports.statement(Accounts.store(this.s1), null, null, null, 10).lines()).hasSize(1);
        assertThatThrownBy(() -> this.reports.statement(Accounts.store(this.s3), null, null, null, 10))
            .isInstanceOf(ModuleException.Forbidden.class);

        signIn("staff-1", this.s1, "store:portal");
        assertThatThrownBy(() -> this.reports.payouts("STORE", "2026-11-05", "2026-11-05", null))
            .isInstanceOf(ModuleException.Forbidden.class);

        signIn(this.r1, null, "rider:portal");
        assertThat(this.reports.statement(Accounts.rider(this.r1), null, null, null, 5).lines()).isEmpty();
        assertThatThrownBy(() -> this.reports.statement(Accounts.rider("someone-else"), null, null, null, 5))
            .isInstanceOf(ModuleException.Forbidden.class);
    }

    @Test
    @DisplayName("days are counted in the deployment's time zone, periods are checked, and old events post nothing")
    void daysAndChecks() {
        Instant lateEvening = Instant.parse("2026-11-05T20:00:00Z");
        this.posting.orderDone(summary("od-" + this.s1, this.s1, this.b1, OrderStatus.DELIVERED, PaymentMethod.CARD,
                DeliveryMode.SELF), lateEvening);
        assertThat(transactions().stream().filter((t) -> t.storeId().equals(this.s1)).findFirst().orElseThrow().day())
            .isEqualTo("2026-11-06");

        OrderSummary old = new OrderSummary("old-" + this.s1, "N", "c", this.s1, "x", Set.of(), OrderStatus.DELIVERED,
                Fulfilment.DELIVERY, PaymentMethod.CARD, 1, 100, "PKR", null, "en", 0);
        this.posting.orderDone(old, START);
        assertThat(count("old-" + this.s1, LedgerPosting.ORDER_DONE)).isZero();

        signIn("finance-1", null, LedgerReports.LEDGER_READ);
        assertThatThrownBy(() -> this.reports.payouts("STORE", "2026-11-05", "2026-11-01", null))
            .isInstanceOfSatisfying(ModuleException.BadRequest.class, (ex) -> assertThat(ex.code()).isEqualTo("LEDGER_PERIOD_INVALID"));
        assertThatThrownBy(() -> this.reports.payouts("STORE", "2025-01-01", "2026-11-01", null))
            .isInstanceOf(ModuleException.BadRequest.class);
        assertThatThrownBy(() -> this.reports.payouts("STORE", "5 Nov", "2026-11-05", null))
            .isInstanceOfSatisfying(ModuleException.BadRequest.class, (ex) -> assertThat(ex.code()).isEqualTo("LEDGER_DATE_INVALID"));
        assertThatThrownBy(() -> this.reports.payouts("OPERATOR", "2026-11-05", "2026-11-05", null))
            .isInstanceOf(ModuleException.BadRequest.class);
        // a report includes what is not rolled up yet
        assertThat(row(this.reports.payouts("STORE", "2026-11-06", "2026-11-06", this.b1), this.s1).dueMinor())
            .isEqualTo(100_000 + 5_000 + 15_000 + 3_000 - 10_500);
    }

    @Test
    @DisplayName("a direct-collecting branch over its credit limit is announced once, and again once back within it")
    void creditLimit() {
        given(this.settings.getOrdering())
            .willReturn(new OrderingView(true, true, 0, 0, 0, true, 180, 1, false, true, 1_000_000L));
        CreditLimits limits = new CreditLimits(this.ledgerMongo.template(), this.ledgerMongo.transactionTemplate(), this.outbox,
                this.settings, Clock.fixed(START, ZoneOffset.UTC));
        limits.collectionChanged(this.s1, this.b1, "Napoli", Set.of("owner-1"), "DIRECT", 10_000L);
        assertThat(creditEvents(this.s1)).isEmpty();

        String o1 = "o1-" + this.s1;
        OrderMoney money = new OrderMoney(this.b1, DeliveryMode.OPERATOR, OrderMoney.STORE, 100_000, 0, 15_000, 0, 2_000, 5_000,
                3_000, 0, 1_000, 500);
        OrderSummary direct = new OrderSummary(o1, "N-1", "c1", this.s1, "Napoli", Set.of(), OrderStatus.DELIVERED,
                Fulfilment.DELIVERY, PaymentMethod.CARD, 2, money.totalMinor(), "PKR", null, "en", START.getEpochSecond(), money);
        this.posting.orderPlaced(direct);
        this.posting.orderDone(direct, START);
        long owed = -this.reports.storeBalance(this.s1).balanceMinor();
        assertThat(owed).isGreaterThan(10_000);

        limits.check(this.s1);
        limits.check(this.s1);
        assertThat(creditEvents(this.s1)).containsExactly("foodstack.ledger.StoreCreditLimitExceeded.v1");
        assertThat(this.ledgerMongo.template().findById(this.s1, CreditDocument.class).owedMinor()).isEqualTo(owed);

        limits.collectionChanged(this.s1, this.b1, "Napoli", Set.of("owner-1"), "DIRECT", null);
        assertThat(creditEvents(this.s1)).containsExactly("foodstack.ledger.StoreCreditLimitExceeded.v1",
                "foodstack.ledger.StoreCreditRestored.v1");

        limits.collectionChanged(this.s2, this.b1, "Napoli 2", Set.of(), "OPERATOR", 0L);
        limits.check(this.s2);
        assertThat(creditEvents(this.s2)).isEmpty();
    }

    // ---- helpers ------------------------------------------------------------------------------

    private List<String> creditEvents(String storeId) {
        return events().stream()
            .filter((d) -> storeId.equals(d.getString("subject")) && d.getString("type").contains("Credit"))
            .sorted(java.util.Comparator.comparing((Document d) -> String.valueOf(d.get("_id"))))
            .map((d) -> d.getString("type"))
            .toList();
    }

    private static OrderSummary summary(String orderId, String storeId, String brandId, OrderStatus status,
            PaymentMethod method, DeliveryMode mode) {
        OrderMoney money = new OrderMoney(brandId, mode, OrderMoney.OPERATOR, 100_000, 0, 15_000, 0, 2_000, 5_000, 3_000, 0,
                1_000, 500);
        return new OrderSummary(orderId, "N-" + orderId, "c1", storeId, "Napoli " + storeId, Set.of(), status,
                Fulfilment.DELIVERY, method, 2, money.totalMinor(), "PKR", null, "en", START.getEpochSecond(), money);
    }

    private static DeliveryCompleted delivered(String orderId, String storeId, String riderId, long cash) {
        DeliverySummary d = new DeliverySummary(orderId, "N", "c1", storeId, "Napoli", Set.of(), DeliveryMode.OPERATOR,
                DeliveryStatus.DELIVERED, riderId, "Hamza", new GeoPoint(31.5, 74.3), new GeoPoint(31.51, 74.31), "en");
        return new DeliveryCompleted(d, "rider:" + riderId, "OPERATOR", 15_000, 10_500, 3_000, cash, "PKR", 3_000, "TAP");
    }

    private static PayoutRow row(PayoutReport report, String storeId) {
        return report.rows().stream().filter((r) -> r.partyId().equals(storeId)).findFirst().orElseThrow();
    }

    private List<TransactionDocument> transactions() {
        return this.ledgerMongo.template().findAll(TransactionDocument.class);
    }

    private long count(String orderId, String type) {
        return this.ledgerMongo.template()
            .count(Query.query(Criteria.where("orderId").is(orderId).and("type").is(type)), TransactionDocument.class);
    }

    private List<Document> events() {
        return this.ledgerMongo.template().getCollection(this.outbox.collection()).find().into(new ArrayList<>());
    }

    private static void signIn(String subject, String storeId, String... permissions) {
        Jwt.Builder jwt = Jwt.withTokenValue("t").header("alg", "ES256").subject(subject).claim("perms", List.of(permissions));
        if (storeId != null) {
            jwt.claim("storeIds", List.of(storeId));
        }
        SecurityContextHolder.getContext().setAuthentication(new FoodstackJwtAuthenticationConverter().convert(jwt.build()));
    }

    private static void signInOwner(String brandId, String storeId) {
        Jwt.Builder jwt = Jwt.withTokenValue("t").header("alg", "ES256").subject("owner-1")
            .claim("perms", List.of("store:portal", LedgerReports.LEDGER_STORE));
        if (brandId != null) {
            jwt.claim("brandIds", List.of(brandId));
        }
        if (storeId != null) {
            jwt.claim("storeIds", List.of(storeId));
        }
        SecurityContextHolder.getContext().setAuthentication(new FoodstackJwtAuthenticationConverter().convert(jwt.build()));
    }
}
