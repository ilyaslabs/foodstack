package io.github.ilyaslabs.foodstack.store.core.application;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import io.github.ilyaslabs.foodstack.geo.api.DeliveryAreaRequest;
import io.github.ilyaslabs.foodstack.geo.api.DeliveryAreaView;
import io.github.ilyaslabs.foodstack.geo.api.DeliveryMode;
import io.github.ilyaslabs.foodstack.geo.api.DeliveryQuoteView;
import io.github.ilyaslabs.foodstack.geo.api.GeoApi;
import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import io.github.ilyaslabs.foodstack.geo.api.QuoteRequest;
import io.github.ilyaslabs.foodstack.identity.api.IdentityApi;
import io.github.ilyaslabs.foodstack.identity.api.Role;
import io.github.ilyaslabs.foodstack.identity.api.StoreAccessGrant;
import io.github.ilyaslabs.foodstack.identity.api.UserStatus;
import io.github.ilyaslabs.foodstack.identity.api.UserView;
import io.github.ilyaslabs.foodstack.media.api.MediaApi;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.settings.api.LocaleView;
import io.github.ilyaslabs.foodstack.settings.api.SettingsApi;
import io.github.ilyaslabs.foodstack.store.api.StoreStatus;
import io.github.ilyaslabs.foodstack.store.api.TimeRange;
import io.github.ilyaslabs.foodstack.store.api.events.BrandChanged;
import io.github.ilyaslabs.foodstack.store.api.events.StoreAvailabilityChanged;
import io.github.ilyaslabs.foodstack.store.api.events.StoreChanged;
import io.github.ilyaslabs.foodstack.store.core.domain.BranchTimeZones;
import io.github.ilyaslabs.foodstack.store.core.persistence.BrandDocument;
import io.github.ilyaslabs.foodstack.store.core.persistence.BrandRepository;
import io.github.ilyaslabs.foodstack.store.core.persistence.MenuItemRepository;
import io.github.ilyaslabs.foodstack.store.core.persistence.StoreDocument;
import io.github.ilyaslabs.foodstack.store.core.persistence.StoreRepository;
import io.github.ilyaslabs.foodstack.store.core.persistence.StoreSlugDocument;
import io.github.ilyaslabs.foodstack.store.core.persistence.StoreSlugRepository;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class StoreServiceTest {

    // Saturday 2026-10-31 14:00 in Asia/Karachi
    private static final Instant NOW = Instant.parse("2026-10-31T09:00:00Z");

    private static final LocaleView LOCALE = new LocaleView("en", List.of("en", "ar", "ur"), "PKR", "Asia/Karachi", 1);

    private final BrandRepository brands = mock(BrandRepository.class);

    private final StoreRepository stores = mock(StoreRepository.class);

    private final StoreSlugRepository slugs = mock(StoreSlugRepository.class);

    private final MenuItemRepository items = mock(MenuItemRepository.class);

    private final SettingsApi settings = mock(SettingsApi.class);

    private final MediaApi media = mock(MediaApi.class);

    private final IdentityApi identity = mock(IdentityApi.class);

    private final GeoApi geo = mock(GeoApi.class);

    private final OutboxWriter outbox = mock(OutboxWriter.class);

    private final TransactionTemplate transaction = mock(TransactionTemplate.class);

    private final StoreService service = new StoreService(this.brands, this.stores, this.slugs, this.items, this.settings, this.media,
            this.identity, this.geo, this.outbox, this.transaction, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        given(this.settings.getLocale()).willReturn(LOCALE);
        given(this.transaction.execute(any())).willAnswer((invocation) -> ((TransactionCallback<Object>) invocation
            .getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
        org.mockito.Mockito.doAnswer((invocation) -> {
            ((java.util.function.Consumer<TransactionStatus>) invocation.getArgument(0)).accept(mock(TransactionStatus.class));
            return null;
        }).when(this.transaction).executeWithoutResult(any());
        given(this.brands.insert(any(BrandDocument.class))).willAnswer((invocation) -> versioned(invocation.getArgument(0)));
        given(this.brands.save(any(BrandDocument.class))).willAnswer((invocation) -> versioned(invocation.getArgument(0)));
        given(this.stores.insert(any(StoreDocument.class))).willAnswer((invocation) -> versioned(invocation.getArgument(0)));
        given(this.stores.save(any(StoreDocument.class))).willAnswer((invocation) -> versioned(invocation.getArgument(0)));
    }

    @Test
    @DisplayName("a second branch with the same names gets a numbered web address")
    void onboardTakenSlug() {
        given(this.slugs.existsById("napoli-slice")).willReturn(true);

        StoreDetails store = this.service.onboard(new NewStore("Napoli Slice", null, "Napoli Slice Ltd", null,
                Map.of("en", "Pizza"), List.of("italian"), new Address("2 Main Road", "DHA", "Lahore", null),
                new GeoPoint(31.46, 74.40), DeliveryMode.SELF, true, 20, new Commission(1500, 0)), "admin-1");

        assertThat(store.slug()).isEqualTo("napoli-slice-2");
    }

    @Test
    @DisplayName("the operator changes a branch's web address; the old one is freed and a taken or malformed one refused")
    void updateSlug() {
        StoreDocument store = store(StoreStatus.LIVE);
        store.setSlug("spice-route-gulberg");
        given(this.stores.findById("s1")).willReturn(Optional.of(store));
        given(this.brands.findById("b1")).willReturn(Optional.of(brand()));
        given(this.slugs.existsById("napoli-slice")).willReturn(true);

        StoreDetails updated = this.service.updateSlug("s1", " Spice-Route ", 3, "admin-1");

        assertThat(updated.slug()).isEqualTo("spice-route");
        verify(this.slugs).insert(argThat((StoreSlugDocument s) -> s.getSlug().equals("spice-route")));
        verify(this.slugs).deleteById("spice-route-gulberg");
        assertThatThrownBy(() -> this.service.updateSlug("s1", "napoli-slice", 4, "admin-1"))
            .isInstanceOfSatisfying(ModuleException.class, (ex) -> assertThat(ex.code()).isEqualTo("SLUG_TAKEN"));
        assertThatThrownBy(() -> this.service.updateSlug("s1", "Spice Route!", 4, "admin-1"))
            .isInstanceOfSatisfying(ModuleException.class, (ex) -> assertThat(ex.code()).isEqualTo("STORE_INVALID"));
    }

    @Test
    @DisplayName("links are read by slug or id; an unknown slug is not found")
    void resolve() {
        given(this.slugs.findById("spice-route-gulberg"))
            .willReturn(Optional.of(new StoreSlugDocument("spice-route-gulberg", "s1", NOW)));

        assertThat(this.service.resolve("Spice-Route-Gulberg")).isEqualTo("s1");
        assertThat(this.service.resolve("0195f0a0-0000-7000-8000-000000000101"))
            .isEqualTo("0195f0a0-0000-7000-8000-000000000101");
        assertThatThrownBy(() -> this.service.resolve("nowhere"))
            .isInstanceOfSatisfying(ModuleException.class, (ex) -> assertThat(ex.code()).isEqualTo("STORE_NOT_FOUND"));
    }

    @Test
    @DisplayName("branches without a web address get one at start-up")
    void assignMissingSlugs() {
        StoreDocument store = store(StoreStatus.LIVE);
        given(this.stores.findBySlugIsNull(any())).willReturn(List.of(store), List.of());
        given(this.brands.findById("b1")).willReturn(Optional.of(brand()));

        assertThat(this.service.assignMissingSlugs("system")).isEqualTo(1);
        assertThat(store.getSlug()).isEqualTo("spice-route-gulberg");
        verify(this.slugs).insert(argThat((StoreSlugDocument s) -> s.getStoreId().equals("s1")));
    }

    @Test
    @DisplayName("onboards a restaurant as a brand with its first branch, in DRAFT with default hours, and publishes both events")
    void onboard() {
        // given
        AtomicReference<BrandDocument> brand = new AtomicReference<>();
        given(this.brands.insert(any(BrandDocument.class))).willAnswer((invocation) -> {
            brand.set(versioned(invocation.getArgument(0)));
            return brand.get();
        });

        // when
        StoreDetails store = this.service.onboard(new NewStore(" Spice Route ", "Gulberg", "Spice Route (Pvt) Ltd", null,
                Map.of("en", "Biryani and karahi", "ar", "برياني"), List.of("Pakistani", "BBQ"),
                new Address("1 Main Boulevard", "Gulberg", "Lahore", ""), new GeoPoint(31.52, 74.35), DeliveryMode.SELF,
                true, 25, new Commission(1500, 0)), "admin-1");

        // then
        assertThat(store.status()).isEqualTo(StoreStatus.DRAFT);
        assertThat(store.brand().name()).isEqualTo("Spice Route");
        assertThat(store.brand().cuisines()).containsExactly("pakistani", "bbq");
        assertThat(store.brand().description()).containsOnlyKeys("en", "ar");
        assertThat(store.openingHours()).hasSize(7).containsEntry(DayOfWeek.MONDAY, List.of(new TimeRange("11:00", "23:00")));
        assertThat(store.address().postalCode()).isNull();
        assertThat(store.location()).isEqualTo(new GeoPoint(31.52, 74.35));
        assertThat(store.openNow()).isFalse();
        assertThat(store.timezone()).isEqualTo("Asia/Karachi");
        assertThat(store.slug()).isEqualTo("spice-route-gulberg");
        verify(this.slugs).insert(argThat((StoreSlugDocument s) -> s.getSlug().equals("spice-route-gulberg")
                && s.getStoreId().equals(store.id())));
        verify(this.outbox).append(new BrandChanged(brand.get().getId(), 1, "admin-1"));
        verify(this.outbox).append(new StoreChanged(store.id(), brand.get().getId(), 1, "admin-1"));
    }

    @Test
    @DisplayName("rejects onboarding with every invalid field listed, and operator delivery before the operator allows it")
    void onboardInvalid() {
        assertThatThrownBy(() -> this.service.onboard(new NewStore("", null, "Legal", null, Map.of("fr", "Bonjour"),
                List.of("ok", "!!"), new Address("", null, "Lahore", null), new GeoPoint(120, 0), DeliveryMode.OPERATOR,
                false, 2, new Commission(20_000, -1)), "admin-1"))
            .isInstanceOfSatisfying(ModuleException.BadRequest.class, (ex) -> {
                assertThat(ex.code()).isEqualTo("STORE_INVALID");
                assertThat(ex.getMessage()).isNotBlank();
            });
        verify(this.brands, never()).insert(any(BrandDocument.class));
    }

    @Test
    @DisplayName("updates the branch with the version the owner saw, refusing stale versions and operator delivery not allowed")
    void updateBranch() {
        // given
        StoreDocument store = store(StoreStatus.DRAFT);
        given(this.stores.findById("s1")).willReturn(Optional.of(store));
        given(this.brands.findById("b1")).willReturn(Optional.of(brand()));
        UpdateBranch update = new UpdateBranch("DHA", new Address("2 Phase 5", "DHA", "Lahore", null),
                new GeoPoint(31.47, 74.40), 30, DeliveryMode.OPERATOR, false);

        // then
        assertThatThrownBy(() -> this.service.updateBranch("s1", update, 2, "owner-1"))
            .isInstanceOfSatisfying(ModuleException.Conflict.class,
                    (ex) -> assertThat(ex.code()).isEqualTo("STORE_VERSION_CONFLICT"));
        assertThatThrownBy(() -> this.service.updateBranch("s1", update, 3, "owner-1"))
            .isInstanceOfSatisfying(ModuleException.Conflict.class,
                    (ex) -> assertThat(ex.code()).isEqualTo("OPERATOR_DELIVERY_NOT_ALLOWED"));

        // when
        store.setOperatorDeliveryAllowed(true);
        StoreDetails updated = this.service.updateBranch("s1", update, 3, "owner-1");

        // then
        assertThat(updated.branchName()).isEqualTo("DHA");
        assertThat(updated.deliveryMode()).isEqualTo(DeliveryMode.OPERATOR);
        assertThat(updated.version()).isEqualTo(4);
        verify(this.outbox).append(new StoreChanged("s1", "b1", 4, "owner-1"));
    }

    @Test
    @DisplayName("a branch's time zone follows its location unless the owner chose another one")
    void branchTimezone() {
        // given
        BranchTimeZones timeZones = mock(BranchTimeZones.class);
        given(timeZones.zoneAt(eq(31.47), eq(74.40), any())).willReturn(ZoneId.of("Asia/Karachi"));
        given(timeZones.zoneAt(eq(24.86), eq(67.0), any())).willReturn(ZoneId.of("Asia/Dubai"));
        StoreService withZones = new StoreService(this.brands, this.stores, this.slugs, this.items, this.settings, this.media,
                this.identity, this.geo, this.outbox, this.transaction, Clock.fixed(NOW, ZoneOffset.UTC), timeZones);
        StoreDocument store = store(StoreStatus.DRAFT);
        store.setTimezone(null);
        given(this.stores.findById("s1")).willReturn(Optional.of(store));
        given(this.brands.findById("b1")).willReturn(Optional.of(brand()));
        Address address = new Address("2 Phase 5", "DHA", "Lahore", null);
        GeoPoint lahore = new GeoPoint(31.47, 74.40);
        GeoPoint moved = new GeoPoint(24.86, 67.0);

        // then: an older branch without a zone gets one from its location
        assertThat(withZones.updateBranch("s1", new UpdateBranch(null, address, lahore, 30, DeliveryMode.SELF, false), 3, "o")
            .timezone()).isEqualTo("Asia/Karachi");
        // the owner's choice wins and stays while the branch stays put
        assertThat(withZones.updateBranch("s1",
                new UpdateBranch(null, address, lahore, 30, DeliveryMode.SELF, false, "Asia/Kolkata"), 4, "o")
            .timezone()).isEqualTo("Asia/Kolkata");
        assertThat(withZones.updateBranch("s1",
                new UpdateBranch(null, address, lahore, 30, DeliveryMode.SELF, false, "Asia/Kolkata"), 5, "o")
            .timezone()).isEqualTo("Asia/Kolkata");
        // moving the branch without touching the zone finds it again
        assertThat(withZones.updateBranch("s1",
                new UpdateBranch(null, address, moved, 30, DeliveryMode.SELF, false, "Asia/Kolkata"), 6, "o")
            .timezone()).isEqualTo("Asia/Dubai");
        assertThatThrownBy(() -> withZones.updateBranch("s1",
                new UpdateBranch(null, address, moved, 30, DeliveryMode.SELF, false, "Mars/Base"), 7, "o"))
            .isInstanceOfSatisfying(ModuleException.BadRequest.class, (ex) -> assertThat(ex.code()).isEqualTo("STORE_INVALID"));
    }

    @Test
    @DisplayName("withdrawing operator delivery moves the branch back to self-delivery")
    void updateAdmin() {
        StoreDocument store = store(StoreStatus.LIVE);
        store.setOperatorDeliveryAllowed(true);
        store.setDeliveryMode(DeliveryMode.OPERATOR);
        given(this.stores.findById("s1")).willReturn(Optional.of(store));
        given(this.brands.findById("b1")).willReturn(Optional.of(brand()));

        StoreDetails updated = this.service.updateAdmin("s1", new StoreAdminUpdate("New Legal", " ", false), 3, "admin-1");

        assertThat(updated.deliveryMode()).isEqualTo(DeliveryMode.SELF);
        assertThat(updated.taxId()).isNull();
        assertThat(updated.operatorDeliveryAllowed()).isFalse();
    }

    @Test
    @DisplayName("saves sorted hours and drops closures that already ended; rejects bad times")
    void updateHours() {
        // given
        StoreDocument store = store(StoreStatus.LIVE);
        given(this.stores.findById("s1")).willReturn(Optional.of(store));
        given(this.brands.findById("b1")).willReturn(Optional.of(brand()));

        // when
        StoreDetails updated = this.service.updateHours("s1",
                Map.of(DayOfWeek.SATURDAY, List.of(new TimeRange("18:00", "02:00"), new TimeRange("12:00", "15:00"))),
                List.of(new Closure(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-02"), "old"),
                        new Closure(LocalDate.parse("2026-12-25"), LocalDate.parse("2026-12-25"), " ")),
                3, "owner-1");

        // then
        assertThat(updated.openingHours().get(DayOfWeek.SATURDAY)).extracting(TimeRange::open).containsExactly("12:00", "18:00");
        assertThat(updated.openingHours()).doesNotContainKey(DayOfWeek.MONDAY);
        assertThat(updated.closures()).singleElement().satisfies((c) -> assertThat(c.reason()).isNull());
        assertThat(updated.openNow()).isTrue();
        assertThatThrownBy(() -> this.service.updateHours("s1", Map.of(DayOfWeek.MONDAY, List.of(new TimeRange("25:00", "10:00"))),
                List.of(), 4, "owner-1"))
            .isInstanceOfSatisfying(ModuleException.BadRequest.class, (ex) -> assertThat(ex.code()).isEqualTo("HOURS_INVALID"));
    }

    @Test
    @DisplayName("the temporarily-closed switch closes the branch whatever the timetable and publishes StoreAvailabilityChanged")
    void temporarilyClosed() {
        StoreDocument store = store(StoreStatus.LIVE);
        given(this.stores.findById("s1")).willReturn(Optional.of(store));
        given(this.brands.findById("b1")).willReturn(Optional.of(brand()));

        StoreDetails closed = this.service.setTemporarilyClosed("s1", true, " Kitchen maintenance ", "owner-1");

        assertThat(closed.temporarilyClosed()).isTrue();
        assertThat(closed.openNow()).isFalse();
        assertThat(closed.closedReason()).isEqualTo("Kitchen maintenance");
        verify(this.outbox).append(new StoreAvailabilityChanged("s1", true, "Kitchen maintenance", "owner-1"));
    }

    @Test
    @DisplayName("going live needs a location, a menu and a delivery area (or pickup)")
    void goLive() {
        // given
        StoreDocument store = store(StoreStatus.DRAFT);
        given(this.stores.findById("s1")).willReturn(Optional.of(store));
        given(this.brands.findById("b1")).willReturn(Optional.of(brand()));
        given(this.items.countByBrandIdAndDeletedAtIsNull("b1")).willReturn(0L);
        given(this.geo.deliveryArea(any(DeliveryAreaRequest.class))).willReturn(new DeliveryAreaView("s1", List.of()));

        // then
        assertThat(this.service.readiness("s1")).containsExactly("MENU", "DELIVERY_AREA");
        assertThatThrownBy(() -> this.service.setStatus("s1", StoreStatus.LIVE, "admin-1"))
            .isInstanceOfSatisfying(ModuleException.Conflict.class, (ex) -> assertThat(ex.code()).isEqualTo("STORE_NOT_READY"));

        // when
        given(this.items.countByBrandIdAndDeletedAtIsNull("b1")).willReturn(3L);
        store.setPickupEnabled(true);
        StoreDetails live = this.service.setStatus("s1", StoreStatus.LIVE, "admin-1");

        // then
        assertThat(live.status()).isEqualTo(StoreStatus.LIVE);
    }

    @Test
    @DisplayName("quotes delivery through geo and adds preparation plus travel time")
    void quote() {
        given(this.stores.findById("s1")).willReturn(Optional.of(store(StoreStatus.LIVE)));
        given(this.geo.quote(any(QuoteRequest.class))).willReturn(
                new DeliveryQuoteView(true, null, 10_000, 50_000, null, 5000, "z1", DeliveryMode.SELF));

        DeliveryQuote quote = this.service.quote("s1", new GeoPoint(31.50, 74.33));

        assertThat(quote.deliverable()).isTrue();
        assertThat(quote.etaMinutes()).isEqualTo(20 + 15);
        assertThat(quote.currency()).isEqualTo("PKR");
        verify(this.geo).quote(new QuoteRequest("s1", new GeoPoint(31.52, 74.35), DeliveryMode.SELF, new GeoPoint(31.50, 74.33),
                "Asia/Karachi"));
    }

    @Test
    @DisplayName("gives an owner access through identity and records them on the branch and brand")
    void addOwner() {
        StoreDocument store = store(StoreStatus.LIVE);
        BrandDocument brand = brand();
        given(this.stores.findById("s1")).willReturn(Optional.of(store));
        given(this.brands.findById("b1")).willReturn(Optional.of(brand));
        given(this.identity.grantStoreAccess(any(StoreAccessGrant.class))).willReturn(new UserView("u9", "owner@example.com",
                "Owner", Set.of(Role.STORE_OWNER), UserStatus.ACTIVE, 0, Set.of("b1"), Set.of("s1")));

        OwnerDetails owner = this.service.addOwner("s1", new NewOwner("owner@example.com", "Owner", "secret-pass-1"), "admin-1");

        assertThat(owner.userId()).isEqualTo("u9");
        assertThat(store.getOwnerUserIds()).contains("u9");
        assertThat(brand.getOwnerUserIds()).contains("u9");
        verify(this.identity).grantStoreAccess(
                new StoreAccessGrant("owner@example.com", "Owner", "secret-pass-1", "b1", "s1", "admin-1"));

        this.service.removeOwner("s1", "u9", "admin-1");
        verify(this.identity).revokeStoreAccess("u9", "b1", "s1");
        assertThat(store.getOwnerUserIds()).doesNotContain("u9");
    }

    @Test
    @DisplayName("lists only the owner's branches when ids are given, filtered by name")
    void list() {
        StoreDocument store = store(StoreStatus.LIVE);
        given(this.stores.findByIdInOrderByIdDesc(Set.of("s1"))).willReturn(List.of(store));
        BrandDocument brand = new BrandDocument();
        brand.setId(store.getBrandId());
        brand.setLogoUrls(new LinkedHashMap<>(Map.of("thumb", "https://cdn.example/logo.webp")));
        given(this.brands.findAllById(any())).willReturn(List.of(brand));

        assertThat(this.service.list(Set.of("s1"), "gulb", 10)).singleElement()
            .satisfies((summary) -> {
                assertThat(summary.id()).isEqualTo("s1");
                assertThat(summary.logoUrls()).containsEntry("thumb", "https://cdn.example/logo.webp");
            });
        assertThat(this.service.list(Set.of("s1"), "napoli", 10)).isEmpty();
        verify(this.stores, never()).findAllByOrderByIdDesc(any());
    }

    @Test
    @DisplayName("reports a missing branch as STORE_NOT_FOUND")
    void notFound() {
        given(this.stores.findById(anyString())).willReturn(Optional.empty());
        assertThatThrownBy(() -> this.service.get("nope"))
            .isInstanceOfSatisfying(ModuleException.NotFound.class, (ex) -> assertThat(ex.code()).isEqualTo("STORE_NOT_FOUND"));
    }

    private static <T> T versioned(T document) {
        if (document instanceof StoreDocument store) {
            store.setVersion((store.getVersion() == null) ? 1L : store.getVersion() + 1);
        }
        if (document instanceof BrandDocument brand) {
            brand.setVersion((brand.getVersion() == null) ? 1L : brand.getVersion() + 1);
        }
        return document;
    }

    private static BrandDocument brand() {
        BrandDocument brand = new BrandDocument();
        brand.setId("b1");
        brand.setName("Spice Route");
        brand.setCommission(new Commission(1500, 0));
        brand.setBranchCount(1);
        brand.setVersion(1L);
        return brand;
    }

    private static StoreDocument store(StoreStatus status) {
        StoreDocument store = new StoreDocument();
        store.setId("s1");
        store.setBrandId("b1");
        store.setBrandName("Spice Route");
        store.setBranchName("Gulberg");
        store.setLegalName("Spice Route (Pvt) Ltd");
        store.setAddress(new Address("1 Main Boulevard", "Gulberg", "Lahore", null));
        store.setLocation(new GeoJsonPoint(74.35, 31.52));
        Map<DayOfWeek, List<TimeRange>> hours = new java.util.EnumMap<>(DayOfWeek.class);
        for (DayOfWeek day : DayOfWeek.values()) {
            hours.put(day, List.of(new TimeRange("11:00", "23:00")));
        }
        store.setOpeningHours(hours);
        store.setPrepMinutes(20);
        store.setDeliveryMode(DeliveryMode.SELF);
        store.setStatus(status);
        store.setVersion(3L);
        return store;
    }
}
