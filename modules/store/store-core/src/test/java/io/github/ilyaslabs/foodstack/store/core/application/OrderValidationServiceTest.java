package io.github.ilyaslabs.foodstack.store.core.application;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.github.ilyaslabs.foodstack.geo.api.DeliveryMode;
import io.github.ilyaslabs.foodstack.geo.api.GeoApi;
import io.github.ilyaslabs.foodstack.identity.api.IdentityApi;
import io.github.ilyaslabs.foodstack.media.api.MediaApi;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.settings.api.LocaleView;
import io.github.ilyaslabs.foodstack.settings.api.SettingsApi;
import io.github.ilyaslabs.foodstack.store.api.OrderLineRequest;
import io.github.ilyaslabs.foodstack.store.api.OrderValidationRequest;
import io.github.ilyaslabs.foodstack.store.api.OrderValidationView.LineProblem;
import io.github.ilyaslabs.foodstack.store.api.OrderValidationView;
import io.github.ilyaslabs.foodstack.store.api.StoreStatus;
import io.github.ilyaslabs.foodstack.store.api.TimeRange;
import io.github.ilyaslabs.foodstack.store.core.persistence.BrandDocument;
import io.github.ilyaslabs.foodstack.store.core.persistence.BrandRepository;
import io.github.ilyaslabs.foodstack.store.core.persistence.MenuItemRepository;
import io.github.ilyaslabs.foodstack.store.core.persistence.StoreDocument;
import io.github.ilyaslabs.foodstack.store.core.persistence.StoreRepository;
import io.github.ilyaslabs.foodstack.store.core.persistence.StoreSlugRepository;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class OrderValidationServiceTest {

    // Saturday 2026-10-31 14:00 in Asia/Karachi
    private static final Instant NOW = Instant.parse("2026-10-31T09:00:00Z");

    private final BrandRepository brands = mock(BrandRepository.class);

    private final StoreRepository stores = mock(StoreRepository.class);

    private final SettingsApi settings = mock(SettingsApi.class);

    private final MenuService menus = mock(MenuService.class);

    private final StoreService storeService = new StoreService(this.brands, this.stores, mock(StoreSlugRepository.class),
            mock(MenuItemRepository.class), this.settings, mock(MediaApi.class), mock(IdentityApi.class), mock(GeoApi.class),
            mock(OutboxWriter.class), mock(TransactionTemplate.class), Clock.fixed(NOW, ZoneOffset.UTC));

    private final OrderValidationService service = new OrderValidationService(this.storeService, this.menus, this.settings);

    private StoreDocument store;

    @BeforeEach
    void setUp() {
        given(this.settings.getLocale()).willReturn(new LocaleView("en", List.of("en"), "PKR", "Asia/Karachi", 1));
        this.store = new StoreDocument();
        this.store.setId("s1");
        this.store.setBrandId("b1");
        this.store.setBranchName("Gulberg");
        this.store.setSlug("spice-route-gulberg");
        this.store.setAddress(new Address("1 Main Boulevard", "Gulberg", "Lahore", null));
        this.store.setLocation(new GeoJsonPoint(74.35, 31.52));
        Map<DayOfWeek, List<TimeRange>> hours = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek day : DayOfWeek.values()) {
            hours.put(day, List.of(new TimeRange("11:00", "23:00")));
        }
        this.store.setOpeningHours(hours);
        this.store.setPrepMinutes(20);
        this.store.setDeliveryMode(DeliveryMode.SELF);
        this.store.setPickupEnabled(true);
        this.store.setOwnerUserIds(new java.util.TreeSet<>(Set.of("owner-1")));
        this.store.setStatus(StoreStatus.LIVE);
        BrandDocument brand = new BrandDocument();
        brand.setId("b1");
        brand.setName("Spice Route");
        brand.setCommission(new Commission(1500, 2000));
        given(this.stores.findById("s1")).willReturn(Optional.of(this.store));
        given(this.brands.findById("b1")).willReturn(Optional.of(brand));
        EffectiveMenu.Group size = new EffectiveMenu.Group("g-size", Map.of("en", "Size"), 1, 1,
                List.of(new EffectiveMenu.Option("o-regular", Map.of("en", "Regular"), 0, false),
                        new EffectiveMenu.Option("o-large", Map.of("en", "Large"), 30000, false)));
        EffectiveMenu.Group extras = new EffectiveMenu.Group("g-extra", Map.of("en", "Extras"), 0, 2,
                List.of(new EffectiveMenu.Option("o-raita", Map.of("en", "Raita"), 10000, false),
                        new EffectiveMenu.Option("o-naan", Map.of("en", "Naan"), 5000, true)));
        EffectiveMenu.Item biryani = new EffectiveMenu.Item("i-biryani", Map.of("en", "Biryani"), Map.of(), 85000, Map.of(),
                List.of(), false, List.of(size, extras));
        EffectiveMenu.Item kheer = new EffectiveMenu.Item("i-kheer", Map.of("en", "Kheer"), Map.of(), 30000, Map.of(), List.of(),
                true, List.of());
        given(this.menus.effectiveMenu("s1")).willReturn(new EffectiveMenu("s1", "b1", "PKR",
                List.of(new EffectiveMenu.Section("sec", Map.of("en", "Mains"), List.of(biryani, kheer)))));
    }

    @Test
    @DisplayName("prices each line with its options and snapshots the branch for the order")
    void prices() {
        OrderValidationView view = this.service.validate(new OrderValidationRequest("s1",
                List.of(new OrderLineRequest("i-biryani", 2, List.of("o-large", "o-raita")))));

        assertThat(view.problems()).isEmpty();
        assertThat(view.currency()).isEqualTo("PKR");
        assertThat(view.lines()).singleElement().satisfies((line) -> {
            assertThat(line.unitPriceMinor()).isEqualTo(85000 + 30000 + 10000);
            assertThat(line.lineTotalMinor()).isEqualTo(2 * 125000);
            assertThat(line.options()).extracting("optionId").containsExactly("o-large", "o-raita");
        });
        assertThat(view.subtotalMinor()).isEqualTo(250000);
        OrderValidationView.StoreSnapshot snapshot = view.store();
        assertThat(snapshot.openNow()).isTrue();
        assertThat(snapshot.live()).isTrue();
        assertThat(snapshot.brandName()).isEqualTo("Spice Route");
        assertThat(snapshot.addressLine()).isEqualTo("1 Main Boulevard, Gulberg, Lahore");
        assertThat(snapshot.city()).isEqualTo("Lahore");
        assertThat(snapshot.commissionBasisPoints()).isEqualTo(1500);
        assertThat(snapshot.commissionFixedMinor()).isEqualTo(2000);
        assertThat(snapshot.ownerUserIds()).containsExactly("owner-1");
        assertThat(snapshot.location().lat()).isEqualTo(31.52);
    }

    @Test
    @DisplayName("lists what changed instead of failing: gone, sold out, missing choice, unknown option, bad quantity")
    void problems() {
        this.store.setTemporarilyClosed(true);

        OrderValidationView view = this.service.validate(new OrderValidationRequest("s1", List.of(
                new OrderLineRequest("i-gone", 1, List.of()),
                new OrderLineRequest("i-kheer", 1, List.of()),
                new OrderLineRequest("i-biryani", 0, List.of("o-naan", "o-old")))));

        assertThat(view.problems()).containsExactly(
                new LineProblem(0, "i-gone", OrderValidationView.ITEM_NOT_FOUND, null),
                new LineProblem(1, "i-kheer", OrderValidationView.ITEM_SOLD_OUT, null),
                new LineProblem(2, "i-biryani", OrderValidationView.QUANTITY_INVALID, null),
                new LineProblem(2, "i-biryani", OrderValidationView.CHOICES_INVALID, "g-size"),
                new LineProblem(2, "i-biryani", OrderValidationView.OPTION_SOLD_OUT, "o-naan"),
                new LineProblem(2, "i-biryani", OrderValidationView.OPTION_NOT_FOUND, "o-old"));
        assertThat(view.store().openNow()).isFalse();
    }

    @Test
    @DisplayName("refuses an unknown branch and an absurd number of lines")
    void refuses() {
        assertThatThrownBy(() -> this.service.validate(new OrderValidationRequest("nope", List.of())))
            .isInstanceOfSatisfying(ModuleException.class, (ex) -> assertThat(ex.code()).isEqualTo("STORE_NOT_FOUND"));
        List<OrderLineRequest> many = java.util.Collections.nCopies(101, new OrderLineRequest("i-biryani", 1, List.of()));
        assertThatThrownBy(() -> this.service.validate(new OrderValidationRequest("s1", many)))
            .isInstanceOfSatisfying(ModuleException.class, (ex) -> assertThat(ex.code()).isEqualTo("TOO_MANY_LINES"));
    }
}
