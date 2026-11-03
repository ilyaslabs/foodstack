package io.github.ilyaslabs.foodstack.dispatch.core.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import io.github.ilyaslabs.foodstack.dispatch.api.DeliveryStatus;
import io.github.ilyaslabs.foodstack.dispatch.core.DispatchConfiguration;
import io.github.ilyaslabs.foodstack.dispatch.core.persistence.ActiveJobDocument;
import io.github.ilyaslabs.foodstack.dispatch.core.persistence.JobDocument;
import io.github.ilyaslabs.foodstack.geo.api.DeliveryMode;
import io.github.ilyaslabs.foodstack.geo.api.GeoApi;
import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import io.github.ilyaslabs.foodstack.geo.api.RouteView;
import io.github.ilyaslabs.foodstack.order.api.OrderDelivery;
import io.github.ilyaslabs.foodstack.order.api.OrderStatus;
import io.github.ilyaslabs.foodstack.order.api.OrderSummary;
import io.github.ilyaslabs.foodstack.platform.core.Fulfilment;
import io.github.ilyaslabs.foodstack.platform.core.PaymentMethod;
import io.github.ilyaslabs.foodstack.platform.mongo.FoodstackMongoProperties;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMigrations;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongo;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongoFactory;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.platform.security.FoodstackJwtAuthenticationConverter;
import io.github.ilyaslabs.foodstack.platform.security.PiiCipher;
import io.github.ilyaslabs.foodstack.platform.security.RateLimiter;
import io.github.ilyaslabs.foodstack.platform.security.ServiceCalls;
import io.github.ilyaslabs.foodstack.platform.test.FoodstackContainers;
import io.github.ilyaslabs.foodstack.rider.api.RiderApi;
import io.github.ilyaslabs.foodstack.rider.api.RiderCandidate;
import io.github.ilyaslabs.foodstack.rider.api.RiderKind;
import io.github.ilyaslabs.foodstack.rider.api.RiderSearch;
import io.github.ilyaslabs.foodstack.rider.api.RiderStatus;
import io.github.ilyaslabs.foodstack.rider.api.RiderView;
import io.github.ilyaslabs.foodstack.rider.api.Vehicle;
import io.github.ilyaslabs.foodstack.settings.api.DeliveryView;
import io.github.ilyaslabs.foodstack.settings.api.SettingsApi;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Delivery jobs from acceptance to delivery against a real two-shard MongoDB and a real Redis
 * (TDD 15.3), with riders, settings and geo mocked.
 */
@SpringBootTest(classes = DispatchStorageTest.App.class, properties = "spring.data.mongodb.repositories.type=none")
class DispatchStorageTest {

    private static final Instant START = Instant.parse("2026-11-03T12:00:00Z");

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {

        @Bean
        ModuleMongo dispatchMongo(ModuleMongoFactory factory) {
            return factory.create("dispatch");
        }

        @Bean
        ModuleMigrations dispatchMigrations(ModuleMongo dispatchMongo, ModuleMongoFactory factory,
                FoodstackMongoProperties properties) {
            return new ModuleMigrations(dispatchMongo, factory.connectionString("dispatch"), DispatchConfiguration.shardKeys(),
                    properties);
        }

        @Bean(destroyMethod = "destroy")
        RedisMessageListenerContainer dispatchLiveListeners(RedisConnectionFactory connectionFactory) {
            RedisMessageListenerContainer container = new RedisMessageListenerContainer();
            container.setConnectionFactory(connectionFactory);
            return container;
        }
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        FoodstackContainers.registerMongo(registry);
        FoodstackContainers.registerRedis(registry);
    }

    @Autowired
    ModuleMongo dispatchMongo;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    RedisMessageListenerContainer dispatchLiveListeners;

    private final AtomicReference<Instant> now = new AtomicReference<>(START);

    private final RiderApi riders = mock(RiderApi.class);

    private final SettingsApi settings = mock(SettingsApi.class);

    private final GeoApi geo = mock(GeoApi.class);

    private final ServiceCalls serviceCalls = mock(ServiceCalls.class);

    private final PiiCipher pii = new PiiCipher(Base64.getEncoder().encodeToString(new byte[32]));

    private DispatchService dispatch;

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
                return DispatchStorageTest.this.now.get();
            }
        };
        String namespace = "t" + System.nanoTime();
        willAnswer((invocation) -> invocation.<java.util.function.Supplier<?>>getArgument(0).get()).given(this.serviceCalls)
            .call(any());
        given(this.settings.getDelivery()).willReturn(new DeliveryView(7000, 5_000, 30, 5000, 10, 300, 100_000L, 30, 0));
        given(this.geo.route(any())).willReturn(new RouteView(2_400, 400, false));
        given(this.riders.getRider("r1")).willReturn(rider("r1", "Ali", RiderKind.OPERATOR, null));
        given(this.riders.getRider("r2")).willReturn(rider("r2", "Bilal", RiderKind.OPERATOR, null));
        given(this.riders.getRider("sr1")).willReturn(rider("sr1", "Hamza", RiderKind.STORE, "s1"));
        given(this.riders.getRider("sr2")).willReturn(rider("sr2", "Usman", RiderKind.STORE, "s1"));
        OutboxWriter outbox = new OutboxWriter(this.dispatchMongo, JsonMapper.builder().build(), clock);
        this.dispatch = new DispatchService(this.dispatchMongo.template(), this.dispatchMongo.transactionTemplate(), outbox,
                new DispatchLive(this.redis, this.dispatchLiveListeners, namespace), this.riders, this.settings, this.geo,
                this.serviceCalls, new RateLimiter(this.redis, namespace, clock), this.pii, clock);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("an operator delivery: search from the lead time, offer, accept, pickup and delivery with the customer's code")
    void operatorDelivery() {
        String id = order();
        this.dispatch.orderAccepted(summary(id), delivery(DeliveryMode.OPERATOR, null, "4821", 0), START.plusSeconds(1_200)
            .getEpochSecond(), "store:owner-1");
        this.dispatch.orderAccepted(summary(id), delivery(DeliveryMode.OPERATOR, null, "4821", 0), START.plusSeconds(1_200)
            .getEpochSecond(), "store:owner-1");
        JobDocument job = job(id);
        assertThat(job.status()).isEqualTo("SEARCHING");
        assertThat(job.searchFrom()).isEqualTo(START.plusSeconds(600));
        assertThat(job.distanceMeters()).isEqualTo(2_400);

        given(this.riders.findAvailable(any())).willReturn(List.of(new RiderCandidate("r2", "Bilal", Vehicle.CAR, 1_900, 0, 100),
                new RiderCandidate("r1", "Ali", Vehicle.MOTORBIKE, 400, 600, 90)));
        assertThat(this.dispatch.match()).isZero();
        this.dispatch.orderReady(id);
        assertThat(this.dispatch.match()).isEqualTo(1);
        assertThat(job(id).offerRiderId()).isEqualTo("r1");
        assertThat(lastEventData(id)).contains("\"earningMinor\":10500").contains("\"riderId\":\"r1\"");

        signIn("r2", null, "rider:portal");
        assertThat(this.dispatch.myJobs()).isEmpty();
        assertThatThrownBy(() -> this.dispatch.accept(id))
            .isInstanceOfSatisfying(ModuleException.Conflict.class, (ex) -> assertThat(ex.code()).isEqualTo("OFFER_GONE"));
        signIn("r1", null, "rider:portal");
        assertThat(this.dispatch.myJobs()).singleElement().satisfies((j) -> {
            assertThat(j.status()).isEqualTo(DeliveryStatus.OFFERED);
            assertThat(j.dropoffAddress()).isNull();
            assertThat(j.needsCode()).isTrue();
        });
        JobDetails accepted = this.dispatch.accept(id);
        assertThat(accepted.status()).isEqualTo(DeliveryStatus.ASSIGNED);
        assertThat(accepted.customerPhone()).isEqualTo("+92 300 1234567");
        this.dispatch.arrived(id);
        this.dispatch.pickedUp(id);
        assertThatThrownBy(() -> this.dispatch.delivered(id, new Delivered(null, null, null)))
            .isInstanceOfSatisfying(ModuleException.BadRequest.class,
                    (ex) -> assertThat(ex.code()).isEqualTo("DELIVERY_CODE_REQUIRED"));
        assertThatThrownBy(() -> this.dispatch.delivered(id, new Delivered("1111", null, null)))
            .isInstanceOfSatisfying(ModuleException.BadRequest.class, (ex) -> assertThat(ex.code()).isEqualTo("DELIVERY_CODE_WRONG"));
        JobDetails done = this.dispatch.delivered(id, new Delivered(" 4821 ", null, null));
        assertThat(done.status()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(this.dispatchMongo.template().findById(id, ActiveJobDocument.class)).isNull();
        assertThat(events(id)).containsExactly("RiderOffered", "RiderAssigned", "DeliveryPickedUp", "DeliveryCompleted");
        assertThat(lastEventData(id)).contains("\"proof\":\"CODE\"").contains("\"tipMinor\":2000")
            .contains("\"earningMinor\":10500");
    }

    @Test
    @DisplayName("a lapsed or declined offer moves on to the next rider; nobody near widens the search and alerts support")
    void offersMoveOn() {
        String id = order();
        this.dispatch.orderAccepted(summary(id), delivery(DeliveryMode.OPERATOR, null, null, 0), START.getEpochSecond(),
                "store:owner-1");
        given(this.riders.findAvailable(any())).willAnswer((invocation) -> {
            RiderSearch search = invocation.getArgument(0);
            return List.of(new RiderCandidate("r1", "Ali", Vehicle.CAR, 300, 0, 100),
                    new RiderCandidate("r2", "Bilal", Vehicle.CAR, 900, 0, 100))
                .stream()
                .filter((c) -> !search.excludeRiderIds().contains(c.riderId()))
                .toList();
        });
        this.dispatch.match();
        assertThat(job(id).offerRiderId()).isEqualTo("r1");

        this.now.set(START.plusSeconds(31));
        this.dispatch.match();
        JobDocument second = job(id);
        assertThat(second.offerRiderId()).isEqualTo("r2");
        assertThat(second.triedRiderIds()).containsExactly("r1");

        signIn("r2", null, "rider:portal");
        this.dispatch.decline(id);
        assertThat(job(id).status()).isEqualTo("SEARCHING");
        ArgumentCaptor<RiderSearch> searches = ArgumentCaptor.forClass(RiderSearch.class);
        verify(this.riders, atLeastOnce()).findAvailable(searches.capture());
        assertThat(searches.getValue().excludeRiderIds()).containsExactly("r1");
        org.mockito.BDDMockito.willReturn(List.of()).given(this.riders).findAvailable(any());
        this.now.set(START.plusSeconds(100));
        this.dispatch.match();
        assertThat(job(id).radiusMeters()).isEqualTo(7_500);
        this.now.set(START.plusSeconds(301));
        this.dispatch.match();
        JobDocument alerted = job(id);
        assertThat(alerted.alertedAt()).isNotNull();
        assertThat(alerted.widenings()).isEqualTo(2);
        assertThat(events(id)).containsExactly("RiderOffered", "RiderOffered", "DispatchAlert");

        // support hands it back to the store, which then delivers with its own rider
        signIn("support-1", null, "dispatch:manage");
        JobDetails back = this.dispatch.handBack(id, "No riders nearby");
        assertThat(back.mode()).isEqualTo(DeliveryMode.SELF);
        assertThat(back.status()).isEqualTo(DeliveryStatus.WAITING);
        signIn("owner-1", "s1");
        assertThat(this.dispatch.assignStoreRider(id, "sr1").riderName()).isEqualTo("Hamza");
        assertThat(lastEventData(id)).contains("\"actor\":\"store:owner-1\"");
    }

    @Test
    @DisplayName("a store rider chosen at accept gets the job; the store swaps riders; cash is collected; cancelling frees the rider")
    void storeDelivery() {
        String id = order();
        this.dispatch.orderAccepted(summary(id), delivery(DeliveryMode.SELF, "sr1", null, 170_000), START.getEpochSecond(),
                "store:owner-1");
        JobDocument job = job(id);
        assertThat(job.status()).isEqualTo("ASSIGNED");
        assertThat(job.earningMinor()).isZero();

        signIn("owner-2", "s2");
        assertThatThrownBy(() -> this.dispatch.assignStoreRider(id, "sr2")).isInstanceOf(ModuleException.Forbidden.class);
        signIn("owner-1", "s1");
        assertThatThrownBy(() -> this.dispatch.assignStoreRider(id, "r1"))
            .isInstanceOfSatisfying(ModuleException.BadRequest.class, (ex) -> assertThat(ex.code()).isEqualTo("STORE_RIDER_INVALID"));
        assertThat(this.dispatch.assignStoreRider(id, "sr2").riderId()).isEqualTo("sr2");

        signIn("sr2", null, "rider:portal");
        this.dispatch.pickedUp(id);
        JobDetails done = this.dispatch.delivered(id, new Delivered(null, "https://cdn/door.jpg", 170_000L));
        assertThat(done.status()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(lastEventData(id)).contains("\"proof\":\"PHOTO\"").contains("\"cashCollectedMinor\":170000")
            .contains("\"earningMinor\":0");
        assertThat(events(id)).containsExactly("RiderAssigned", "RiderUnassigned", "RiderAssigned", "DeliveryPickedUp",
                "DeliveryCompleted");

        String other = order();
        this.dispatch.orderAccepted(summary(other), delivery(DeliveryMode.SELF, "sr1", null, 0), START.getEpochSecond(),
                "store:owner-1");
        this.dispatch.orderClosed(other, "Customer cancelled");
        assertThat(job(other).status()).isEqualTo("CANCELLED");
        assertThat(lastEventData(other)).contains("\"riderId\":\"sr1\"");
        this.dispatch.orderClosed(other, "again");
        assertThat(events(other)).containsExactly("RiderAssigned", "DeliveryCancelled");
    }

    @Test
    @DisplayName("support reassigns before pickup only and sees the open board")
    void support() {
        String id = order();
        this.dispatch.orderAccepted(summary(id), delivery(DeliveryMode.OPERATOR, null, null, 0), START.getEpochSecond(),
                "store:owner-1");
        signIn("owner-1", "s1");
        assertThatThrownBy(() -> this.dispatch.open(null)).isInstanceOf(ModuleException.Forbidden.class);
        signIn("support-1", null, "dispatch:manage");
        assertThat(this.dispatch.open(DeliveryStatus.SEARCHING)).extracting(JobDetails::orderId).contains(id);
        assertThatThrownBy(() -> this.dispatch.reassign(id, "sr1", "Nearest"))
            .isInstanceOfSatisfying(ModuleException.BadRequest.class, (ex) -> assertThat(ex.code()).isEqualTo("RIDER_NOT_SUITABLE"));
        assertThat(this.dispatch.reassign(id, "r1", "Nearest").riderId()).isEqualTo("r1");
        assertThat(this.dispatch.reassign(id, "r2", "Ali's bike broke").riderId()).isEqualTo("r2");
        assertThat(job(id).triedRiderIds()).contains("r1");
        signIn("r2", null, "rider:portal");
        this.dispatch.pickedUp(id);
        signIn("support-1", null, "dispatch:manage");
        assertThatThrownBy(() -> this.dispatch.reassign(id, "r1", "Too late"))
            .isInstanceOfSatisfying(ModuleException.Conflict.class,
                    (ex) -> assertThat(ex.code()).isEqualTo("DELIVERY_STATUS_CHANGED"));
        assertThat(this.dispatch.delivered(id, null).status()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(lastEventData(id)).contains("\"proof\":\"SUPPORT\"");
    }

    private static String order() {
        return "o-" + System.nanoTime();
    }

    private static OrderSummary summary(String id) {
        return new OrderSummary(id, "AB12-CD34", "customer-1", "s1", "Spice Route", Set.of("owner-1"), OrderStatus.ACCEPTED,
                Fulfilment.DELIVERY, PaymentMethod.CARD, 2, 170_000, "PKR", null, "en", START.getEpochSecond());
    }

    private OrderDelivery delivery(DeliveryMode mode, String storeRider, String code, long cash) {
        return new OrderDelivery(mode, storeRider, new GeoPoint(31.52, 74.35), "1 Main Boulevard", new GeoPoint(31.5, 74.3),
                "12 Canal Road", "Gate 2", "Sara", this.pii.encrypt("+92 300 1234567"), 15_000, 2_000, cash, "PKR",
                (code != null) ? this.pii.encrypt(code) : null);
    }

    private static RiderView rider(String id, String name, RiderKind kind, String storeId) {
        return new RiderView(id, kind, storeId, name, Vehicle.MOTORBIKE, RiderStatus.ACTIVE, true, 31.52, 74.35, null);
    }

    private JobDocument job(String id) {
        return this.dispatchMongo.template().findById(id, JobDocument.class);
    }

    private List<String> events(String id) {
        return outbox(id).stream().map((d) -> d.getString("type").replace("foodstack.dispatch.", "").replace(".v1", "")).toList();
    }

    private String lastEventData(String id) {
        List<Document> all = outbox(id);
        return all.get(all.size() - 1).get("data", String.class);
    }

    private List<Document> outbox(String id) {
        return this.dispatchMongo.template()
            .find(Query.query(Criteria.where("subject").is(id)), Document.class, "dispatch_outbox")
            .stream()
            .sorted(java.util.Comparator.comparing((Document d) -> d.getString("_id")))
            .toList();
    }

    private static void signIn(String subject, String storeId, String... permissions) {
        Jwt.Builder jwt = Jwt.withTokenValue("t").header("alg", "ES256").subject(subject).claim("perms", List.of(permissions));
        if (storeId != null) {
            jwt.claim("storeIds", List.of(storeId)).claim("perms", List.of("store:portal"));
        }
        SecurityContextHolder.getContext().setAuthentication(new FoodstackJwtAuthenticationConverter().convert(jwt.build()));
    }
}
