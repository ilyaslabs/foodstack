package io.github.ilyaslabs.foodstack.rider.core.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import io.github.ilyaslabs.foodstack.geo.api.GeoApi;
import io.github.ilyaslabs.foodstack.geo.api.RouteView;
import io.github.ilyaslabs.foodstack.identity.api.IdentityApi;
import io.github.ilyaslabs.foodstack.identity.api.Role;
import io.github.ilyaslabs.foodstack.identity.api.UserStatus;
import io.github.ilyaslabs.foodstack.identity.api.UserView;
import io.github.ilyaslabs.foodstack.media.api.MediaApi;
import io.github.ilyaslabs.foodstack.media.api.MediaAssetView;
import io.github.ilyaslabs.foodstack.media.api.MediaOwner;
import io.github.ilyaslabs.foodstack.media.api.MediaPurpose;
import io.github.ilyaslabs.foodstack.media.api.MediaStatus;
import io.github.ilyaslabs.foodstack.media.api.MediaVariantView;
import io.github.ilyaslabs.foodstack.platform.core.GeoCells;
import io.github.ilyaslabs.foodstack.platform.core.Ids;
import io.github.ilyaslabs.foodstack.platform.kafka.LiveChannels;
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
import io.github.ilyaslabs.foodstack.rider.api.RiderCandidate;
import io.github.ilyaslabs.foodstack.rider.api.RiderSearch;
import io.github.ilyaslabs.foodstack.rider.api.RiderStatus;
import io.github.ilyaslabs.foodstack.rider.api.Vehicle;
import io.github.ilyaslabs.foodstack.rider.core.RiderConfiguration;
import io.github.ilyaslabs.foodstack.rider.core.persistence.AssignmentDocument;
import io.github.ilyaslabs.foodstack.rider.core.persistence.EarningDocument;
import io.github.ilyaslabs.foodstack.rider.core.persistence.LocationDocument;
import io.github.ilyaslabs.foodstack.settings.api.DeliveryView;
import io.github.ilyaslabs.foodstack.settings.api.SettingsApi;
import io.github.ilyaslabs.moduleswitch.ModuleException;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Riders, their presence and their deliveries against a real two-shard MongoDB and a real Redis
 * (TDD 15.3), with identity, settings and geo mocked.
 */
@SpringBootTest(classes = RiderStorageTest.App.class, properties = "spring.data.mongodb.repositories.type=none")
class RiderStorageTest {

    private static final Instant START = Instant.parse("2026-11-03T09:00:00Z");

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {

        @Bean
        ModuleMongo riderMongo(ModuleMongoFactory factory) {
            return factory.create("rider");
        }

        @Bean
        ModuleMigrations riderMigrations(ModuleMongo riderMongo, ModuleMongoFactory factory, FoodstackMongoProperties properties) {
            return new ModuleMigrations(riderMongo, factory.connectionString("rider"), RiderConfiguration.shardKeys(), properties);
        }

    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        FoodstackContainers.registerMongo(registry);
        FoodstackContainers.registerRedis(registry);
    }

    @Autowired
    ModuleMongo riderMongo;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    LiveChannels liveChannels;

    private final AtomicReference<Instant> now = new AtomicReference<>(START);

    private final IdentityApi identity = mock(IdentityApi.class);

    private final SettingsApi settings = mock(SettingsApi.class);

    private final GeoApi geo = mock(GeoApi.class);

    private final ServiceCalls serviceCalls = mock(ServiceCalls.class);

    private final PiiCipher pii = new PiiCipher(Base64.getEncoder().encodeToString(new byte[32]));

    private final MediaApi media = mock(MediaApi.class);

    private RiderService riders;

    private RiderOnboarding onboarding;

    private RiderActivity activity;

    private String namespace;

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
                return RiderStorageTest.this.now.get();
            }
        };
        this.namespace = "t" + System.nanoTime();
        willAnswer((invocation) -> invocation.<java.util.function.Supplier<?>>getArgument(0).get()).given(this.serviceCalls)
            .call(any());
        willAnswer((invocation) -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).given(this.serviceCalls).run(any());
        given(this.identity.grantRiderAccess(any())).willAnswer((invocation) -> new UserView(Ids.newId(), "r@example.com",
                "R", Set.of(Role.RIDER), UserStatus.ACTIVE, 0, Set.of(), Set.of()));
        given(this.settings.getDelivery()).willReturn(new DeliveryView(7000, 0, 30, 5000, 10, 300, null, 30, 0));
        OutboxWriter outbox = new OutboxWriter(this.riderMongo, JsonMapper.builder().build(), clock);
        RiderPresence presence = new RiderPresence(this.redis, this.liveChannels, this.namespace);
        this.riders = new RiderService(this.riderMongo.template(), this.riderMongo.transactionTemplate(), outbox, this.identity,
                this.serviceCalls, presence, this.pii, clock);
        this.activity = new RiderActivity(this.riderMongo.template(), this.riderMongo.transactionTemplate(), outbox, this.riders,
                presence, new RateLimiter(this.redis, this.namespace, clock), this.settings, this.geo, this.serviceCalls, clock);
        this.onboarding = new RiderOnboarding(this.riderMongo.template(), this.riders, this.media, this.serviceCalls, this.pii,
                clock);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("riders sit in one GEO set per map cell: a rider who crosses a cell line moves sets, and a search near the line reads both (TDD 7.5)")
    void geoCells() {
        RiderPresence presence = new RiderPresence(this.redis, this.liveChannels, this.namespace);
        // 31.49 and 31.51 lie on either side of the 31.5 cell line, about 2.2 km apart
        presence.moved("cell-rider", new RiderPresence.Position(31.49, 74.34, 1), true);
        assertThat(this.redis.hasKey(this.namespace + ":rider:geo:" + GeoCells.of(31.49, 74.34))).isTrue();
        assertThat(presence.near(31.505, 74.34, 3_000, 5)).extracting(RiderPresence.Nearby::riderId).containsExactly("cell-rider");

        presence.moved("cell-rider", new RiderPresence.Position(31.51, 74.34, 2), true);
        assertThat(this.redis.opsForZSet().score(this.namespace + ":rider:geo:" + GeoCells.of(31.49, 74.34), "cell-rider"))
            .isNull();
        assertThat(presence.near(31.495, 74.34, 3_000, 5)).extracting(RiderPresence.Nearby::riderId).containsExactly("cell-rider");

        presence.unmatchable("cell-rider");
        assertThat(presence.near(31.51, 74.34, 3_000, 5)).isEmpty();
    }

    @Test
    @DisplayName("an operator rider goes online, is found nearest first, drops out while busy and comes back when free")
    void matching() {
        signIn("admin", null, "rider:manage");
        RiderDetails near = this.riders.addOperatorRider(input("Ali", Vehicle.MOTORBIKE));
        RiderDetails far = this.riders.addOperatorRider(input("Bilal", Vehicle.BICYCLE));
        assertThat(near.phone()).isEqualTo("+92 300 1234567");
        assertThat(this.riderMongo.template().findById(near.id(), org.bson.Document.class, "rider_riders").getString("phoneCipher"))
            .doesNotContain("300");

        signIn(near.id(), null, "rider:portal");
        this.activity.setOnline(true, new LocationPing(31.5205, 74.3505, 8.0, null));
        signIn(far.id(), null, "rider:portal");
        this.activity.setOnline(true, new LocationPing(31.54, 74.37, 8.0, null));

        List<RiderCandidate> found = this.activity.findAvailable(new RiderSearch(31.52, 74.35, 5000, 5, Set.of()));
        assertThat(found).extracting(RiderCandidate::riderId).containsExactly(near.id(), far.id());
        assertThat(found.get(0).distanceMeters()).isLessThan(100);
        assertThat(found.get(0).acceptancePercent()).isEqualTo(100);
        assertThat(this.activity.findAvailable(new RiderSearch(31.52, 74.35, 5000, 5, Set.of(near.id()))))
            .extracting(RiderCandidate::riderId).containsExactly(far.id());

        this.activity.offered(near.id());
        this.activity.offered(near.id());
        this.activity.assigned(assignment("o1", near.id()), true);
        assertThat(this.activity.findAvailable(new RiderSearch(31.52, 74.35, 5000, 5, Set.of())))
            .extracting(RiderCandidate::riderId).containsExactly(far.id());
        signIn("customer-1");
        assertThat(this.activity.tracking("o1").riderName()).isEqualTo("Ali");

        this.now.set(START.plusSeconds(600));
        this.activity.delivered(new EarningDocument("o1", near.id(), "AB12-CD34", "Spice Route", 10_500, 2_000, 0, "PKR", 2_400,
                this.now.get()));
        this.activity.delivered(new EarningDocument("o1", near.id(), "AB12-CD34", "Spice Route", 10_500, 2_000, 0, "PKR", 2_400,
                this.now.get()));
        // a rider is found again once free, after a fresh ping
        signIn(near.id(), null, "rider:portal");
        this.activity.locations(List.of(new LocationPing(31.5205, 74.3505, null, null)));
        this.activity.locations(List.of(new LocationPing(31.5206, 74.3506, null, null)));
        List<RiderCandidate> again = this.activity.findAvailable(new RiderSearch(31.52, 74.35, 5000, 5, Set.of(far.id())));
        assertThat(again).singleElement().satisfies((c) -> {
            assertThat(c.riderId()).isEqualTo(near.id());
            assertThat(c.acceptancePercent()).isEqualTo(50);
        });
        assertThat(this.activity.me().deliveries()).isEqualTo(1);
        Earnings earnings = this.activity.myEarnings(7, "Asia/Karachi");
        assertThat(earnings.today().deliveries()).isEqualTo(1);
        assertThat(earnings.today().earningMinor()).isEqualTo(10_500);
        assertThat(earnings.today().tipMinor()).isEqualTo(2_000);
        assertThat(earnings.days()).hasSize(7);
        assertThat(earnings.recent()).singleElement().satisfies((r) -> assertThat(r.orderNumber()).isEqualTo("AB12-CD34"));
    }

    @Test
    @DisplayName("positions need an online rider, are sampled into history, and a quiet rider is taken offline")
    void presence() {
        signIn("admin", null, "rider:manage");
        RiderDetails rider = this.riders.addOperatorRider(input("Sara", Vehicle.SCOOTER));
        signIn(rider.id(), null, "rider:portal");
        assertThatThrownBy(() -> this.activity.locations(List.of(new LocationPing(31.5, 74.3, null, null))))
            .isInstanceOfSatisfying(ModuleException.Conflict.class, (ex) -> assertThat(ex.code()).isEqualTo("RIDER_OFFLINE"));
        assertThatThrownBy(() -> this.activity.locations(List.of(new LocationPing(95, 74.3, null, null))))
            .isInstanceOfSatisfying(ModuleException.BadRequest.class, (ex) -> assertThat(ex.code()).isEqualTo("LOCATION_INVALID"));

        this.activity.setOnline(true, null);
        long at = START.getEpochSecond();
        this.activity.locations(List.of(new LocationPing(31.50, 74.30, 5.0, at - 20), new LocationPing(31.51, 74.31, 5.0, at - 10),
                new LocationPing(31.52, 74.32, 5.0, at)));
        List<LocationDocument> history = this.riderMongo.template()
            .find(Query.query(Criteria.where("riderId").is(rider.id())), LocationDocument.class);
        assertThat(history).hasSize(3);
        assertThat(history.get(0).expireAt()).isEqualTo(START.plus(Duration.ofDays(30)));
        assertThat(this.activity.me().lat()).isEqualTo(31.52);

        signIn("admin", null, "rider:manage");
        assertThat(this.activity.live()).extracting(LiveRider::riderId).contains(rider.id());
        // a map loads only the riders of the area it shows (TDD 7.5)
        assertThat(this.activity.live(new RiderActivity.MapBounds(31.4, 74.2, 31.6, 74.4))).extracting(LiveRider::riderId)
            .contains(rider.id());
        assertThat(this.activity.live(new RiderActivity.MapBounds(24.8, 66.9, 25.0, 67.1))).extracting(LiveRider::riderId)
            .doesNotContain(rider.id());
        this.now.set(START.plus(Duration.ofMinutes(6)));
        assertThat(this.activity.sweepStale()).isGreaterThanOrEqualTo(1);
        assertThat(this.riders.get(rider.id()).online()).isFalse();

        RiderDetails suspended = this.riders.setStatus(rider.id(), RiderStatus.SUSPENDED);
        assertThat(suspended.status()).isEqualTo(RiderStatus.SUSPENDED);
        signIn(rider.id(), null, "rider:portal");
        assertThatThrownBy(() -> this.activity.setOnline(true, null))
            .isInstanceOfSatisfying(ModuleException.Forbidden.class, (ex) -> assertThat(ex.code()).isEqualTo("RIDER_SUSPENDED"));
    }

    @Test
    @DisplayName("an operator rider's papers keep numbers encrypted, claim the photos and are complete once ID and payout are in (DR-1)")
    void papers() {
        signIn("admin", null, "rider:manage");
        RiderDetails rider = this.riders.addOperatorRider(input("Usman", Vehicle.MOTORBIKE));
        given(this.media.getAsset(any())).willAnswer((invocation) -> new MediaAssetView(invocation.getArgument(0),
                MediaPurpose.RIDER_DOCUMENT, MediaStatus.READY, java.util.Map.of("thumb",
                        new MediaVariantView("https://media/thumb", 160, 100, 10), "detail",
                        new MediaVariantView("https://media/detail", 1080, 680, 100)),
                null, 0));

        RiderOnboarding.Papers empty = this.onboarding.get(rider.id());
        assertThat(empty.documents()).isEmpty();
        assertThat(empty.complete()).isFalse();
        assertThatThrownBy(() -> this.onboarding.addDocument(rider.id(),
                new RiderOnboarding.DocumentInput("TAX_CARD", "1", null, null, "soon")))
            .isInstanceOfSatisfying(ModuleException.BadRequest.class, (ex) -> {
                assertThat(ex.code()).isEqualTo("RIDER_DOCUMENT_INVALID");
                assertThat(ex.details()).containsKeys("type", "number", "frontMediaId", "expiresOn");
            });

        RiderOnboarding.Papers added = this.onboarding.addDocument(rider.id(),
                new RiderOnboarding.DocumentInput("national_id", "35202-1234567-1", "m-front", "m-back", "2030-01-31"));
        RiderOnboarding.DocumentView doc = added.documents().get(0);
        assertThat(doc.type()).isEqualTo("NATIONAL_ID");
        assertThat(doc.numberLast4()).isEqualTo("5671");
        assertThat(doc.frontUrl()).isEqualTo("https://media/detail");
        assertThat(doc.verified()).isFalse();
        assertThat(added.identityVerified()).isFalse();
        MediaOwner owner = new MediaOwner("rider", "doc-" + doc.id());
        verify(this.media).claim("m-front", MediaPurpose.RIDER_DOCUMENT, owner);
        verify(this.media).claim("m-back", MediaPurpose.RIDER_DOCUMENT, owner);
        org.bson.Document stored = this.riderMongo.template().findById(rider.id(), org.bson.Document.class, "rider_onboarding");
        assertThat(stored.toJson()).doesNotContain("35202");

        RiderOnboarding.Papers verified = this.onboarding.verifyDocument(rider.id(), doc.id());
        assertThat(verified.identityVerified()).isTrue();
        assertThat(verified.complete()).isFalse();
        assertThatThrownBy(() -> this.onboarding.setPayout(rider.id(), new RiderOnboarding.PayoutInput("CHEQUE", "", "", "1")))
            .isInstanceOfSatisfying(ModuleException.BadRequest.class,
                    (ex) -> assertThat(ex.details()).containsKeys("method", "accountName", "provider", "account"));
        RiderOnboarding.Papers paid = this.onboarding.setPayout(rider.id(),
                new RiderOnboarding.PayoutInput("BANK", "Usman Ali", "Meezan Bank", "PK36 SCBL 0000 0011 2345 6702"));
        assertThat(paid.payout().accountLast4()).isEqualTo("6702");
        assertThat(paid.complete()).isTrue();
        assertThat(this.onboarding.completeRiders()).contains(rider.id());
        assertThat(this.riderMongo.template().findById(rider.id(), org.bson.Document.class, "rider_onboarding").toJson())
            .doesNotContain("SCBL");

        // an expired ID no longer counts; removing a document releases its photos
        this.now.set(Instant.parse("2030-02-01T00:00:00Z"));
        assertThat(this.onboarding.get(rider.id()).documents().get(0).expired()).isTrue();
        assertThat(this.onboarding.get(rider.id()).complete()).isFalse();
        assertThat(this.onboarding.removeDocument(rider.id(), doc.id()).documents()).isEmpty();
        verify(this.media).release("m-front", owner);
        verify(this.media).release("m-back", owner);
        assertThatThrownBy(() -> this.onboarding.verifyDocument(rider.id(), doc.id()))
            .isInstanceOfSatisfying(ModuleException.NotFound.class,
                    (ex) -> assertThat(ex.code()).isEqualTo("RIDER_DOCUMENT_NOT_FOUND"));

        // store riders keep no papers here, and other staff cannot see them
        String store = "store-" + System.nanoTime();
        signIn("owner-1", store);
        RiderDetails own = this.riders.addStoreRider(store, input("Hamza", Vehicle.MOTORBIKE));
        assertThatThrownBy(() -> this.onboarding.get(rider.id())).isInstanceOf(ModuleException.Forbidden.class);
        signIn("admin", null, "rider:manage");
        assertThatThrownBy(() -> this.onboarding.get(own.id())).isInstanceOfSatisfying(ModuleException.BadRequest.class,
                (ex) -> assertThat(ex.code()).isEqualTo("RIDER_NOT_OPERATOR"));
    }

    @Test
    @DisplayName("a store adds, edits and removes its own riders; other stores and strangers cannot")
    void storeRiders() {
        String store = "store-" + System.nanoTime();
        signIn("owner-1", store);
        RiderDetails own = this.riders.addStoreRider(store, input("Hamza", Vehicle.MOTORBIKE));
        assertThat(own.storeId()).isEqualTo(store);
        verify(this.identity).grantRiderAccess(org.mockito.ArgumentMatchers.argThat((g) -> store.equals(g.storeId())));
        assertThat(this.riders.storeRiders(store)).extracting(RiderDetails::id).containsExactly(own.id());
        RiderDetails edited = this.riders.update(own.id(),
                new RiderInput(null, null, "Hamza K", "+92 300 7654321", Vehicle.CAR, "lea-123", "Lahore", own.version()));
        assertThat(edited.plate()).isEqualTo("LEA-123");
        assertThatThrownBy(() -> this.riders.update(own.id(), new RiderInput(null, null, "X", "+92 300 7654321", Vehicle.CAR, null,
                null, own.version()))).isInstanceOfSatisfying(ModuleException.Conflict.class,
                        (ex) -> assertThat(ex.code()).isEqualTo("RIDER_CHANGED"));

        signIn("owner-2", "other-store");
        assertThatThrownBy(() -> this.riders.storeRiders(store)).isInstanceOf(ModuleException.Forbidden.class);
        assertThatThrownBy(() -> this.riders.get(own.id())).isInstanceOf(ModuleException.NotFound.class);
        assertThatThrownBy(() -> this.riders.addOperatorRider(input("No", Vehicle.CAR))).isInstanceOf(ModuleException.Forbidden.class);

        this.activity.assigned(assignment("o9", own.id()), false);
        signIn("owner-1", store);
        assertThatThrownBy(() -> this.riders.removeStoreRider(store, own.id()))
            .isInstanceOfSatisfying(ModuleException.Conflict.class, (ex) -> assertThat(ex.code()).isEqualTo("RIDER_BUSY"));
        this.activity.released("o9", own.id());
        this.riders.removeStoreRider(store, own.id());
        assertThat(this.riders.storeRiders(store)).isEmpty();
        verify(this.identity).revokeRiderAccess(own.id());
    }

    @Test
    @DisplayName("the customer follows the rider with an ETA through the store; strangers cannot")
    void tracking() {
        signIn("admin", null, "rider:manage");
        RiderDetails rider = this.riders.addOperatorRider(input("Ali", Vehicle.MOTORBIKE));
        signIn(rider.id(), null, "rider:portal");
        this.activity.setOnline(true, new LocationPing(31.53, 74.36, null, null));
        this.activity.assigned(assignment("o5", rider.id()), true);
        given(this.geo.route(any())).willReturn(new RouteView(1_000, 120, false), new RouteView(3_000, 360, false));

        signIn("customer-1");
        Tracking tracking = this.activity.tracking("o5");
        assertThat(tracking.lat()).isEqualTo(31.53);
        assertThat(tracking.etaSeconds()).isEqualTo(480);
        assertThat(tracking.remainingMeters()).isEqualTo(4_000);
        signIn("someone-else");
        assertThatThrownBy(() -> this.activity.tracking("o5"))
            .isInstanceOfSatisfying(ModuleException.NotFound.class, (ex) -> assertThat(ex.code()).isEqualTo("TRACKING_NOT_FOUND"));
        signIn("owner-1", "s1");
        assertThat(this.activity.requireFollow("o5").riderId()).isEqualTo(rider.id());
    }

    private static RiderInput input(String name, Vehicle vehicle) {
        return new RiderInput(name.toLowerCase() + "@example.com", "a-long-password", name, "+92 300 1234567", vehicle, null,
                "Lahore", null);
    }

    private static AssignmentDocument assignment(String orderId, String riderId) {
        return new AssignmentDocument(orderId, riderId, "Ali", "MOTORBIKE", "customer-1", "s1", Set.of("owner-1"), 31.52, 74.35,
                31.50, 74.30, false, START);
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
