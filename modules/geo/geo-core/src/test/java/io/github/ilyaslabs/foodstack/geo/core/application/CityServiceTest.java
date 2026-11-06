package io.github.ilyaslabs.foodstack.geo.core.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import io.github.ilyaslabs.foodstack.geo.api.events.CityChanged;
import io.github.ilyaslabs.foodstack.geo.core.persistence.CityDocument;
import io.github.ilyaslabs.foodstack.geo.core.persistence.CityRepository;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.settings.api.LocaleView;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class CityServiceTest {

    private static final LocaleView LOCALE = new LocaleView("en", List.of("en", "ur"), "PKR", "Asia/Karachi", 1);

    private final CityRepository repository = mock(CityRepository.class);

    private final OutboxWriter outbox = mock(OutboxWriter.class);

    private final TransactionTemplate transaction = mock(TransactionTemplate.class);

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-06T10:00:00Z"), ZoneOffset.UTC);

    private final CityService service = new CityService(this.repository, this.outbox, this.transaction, this.clock,
            GeoCache.none(), () -> LOCALE, 60_000);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void runTransactions() {
        given(this.transaction.execute(any())).willAnswer(
                (invocation) -> ((TransactionCallback<Object>) invocation.getArgument(0)).doInTransaction(new SimpleTransactionStatus()));
        given(this.repository.save(any(CityDocument.class))).willAnswer((invocation) -> {
            CityDocument city = invocation.getArgument(0);
            return new CityDocument(city.id(), city.name(), city.centreLat(), city.centreLng(), city.active(), city.pricing(),
                    city.updatedAt(), city.updatedBy(), (city.version() != null) ? city.version() + 1 : 0L);
        });
    }

    @Test
    @DisplayName("adds a city with its own pricing and publishes CityChanged")
    void create() {
        CityPricing pricing = new CityPricing(20_000, 1_000, 12_000, 0, null, 150, List.of());

        City city = this.service.create(new CityInput(Map.of("en", " Lahore ", "ur", "لاہور"), new GeoPoint(31.52, 74.35),
                true, pricing), "admin-1");

        assertThat(city.name()).containsEntry("en", "Lahore");
        assertThat(city.pricing()).isEqualTo(pricing);
        verify(this.outbox).append(new CityChanged(city.id(), 0, "admin-1"));
    }

    @Test
    @DisplayName("refuses a missing default-language name, an unknown language, a taken name and bad pricing")
    void validation() {
        given(this.repository.findAll()).willReturn(List.of(document("c1", "Lahore", 31.52, 74.35, true, null)));

        assertThatThrownBy(() -> this.service.create(new CityInput(Map.of("ur", "لاہور", "fr", "x"), new GeoPoint(31, 74),
                true, null), "admin-1"))
            .isInstanceOfSatisfying(ModuleException.BadRequest.class, (ex) -> assertThat(ex.code()).isEqualTo("CITY_INVALID"));
        assertThatThrownBy(() -> this.service.create(new CityInput(Map.of("en", "lahore"), new GeoPoint(31, 74), true, null),
                "admin-1"))
            .isInstanceOf(ModuleException.BadRequest.class);
        assertThatThrownBy(() -> this.service.create(new CityInput(Map.of("en", "Multan"), new GeoPoint(30.2, 71.5), true,
                new CityPricing(0, 0, 500, 0, null, 100, List.of())), "admin-1"))
            .isInstanceOfSatisfying(ModuleException.BadRequest.class, (ex) -> assertThat(ex.code()).isEqualTo("PRICING_INVALID"));
        verify(this.outbox, never()).append(any());
    }

    @Test
    @DisplayName("refuses a change made on an old version")
    void versionConflict() {
        given(this.repository.findById("c1")).willReturn(Optional.of(document("c1", "Lahore", 31.52, 74.35, true, 3L)));

        assertThatThrownBy(() -> this.service.update("c1", new CityInput(Map.of("en", "Lahore"), new GeoPoint(31.52, 74.35),
                false, null), 2, "admin-1"))
            .isInstanceOfSatisfying(ModuleException.Conflict.class,
                    (ex) -> assertThat(ex.code()).isEqualTo("CITY_VERSION_CONFLICT"));
    }

    @Test
    @DisplayName("suggests the nearest active city within the radius, and only active cities' pricing applies")
    void nearestAndPricing() {
        CityDocument.Pricing own = new CityDocument.Pricing(20_000, 0, 15_000, 0, null, 100, List.of());
        given(this.repository.findAll()).willReturn(List.of(document("lhr", "Lahore", 31.52, 74.35, true, 1L),
                document("khi", "Karachi", 24.86, 67.01, true, 1L, own), document("off", "Kasur", 31.12, 74.45, false, 1L, own)));

        assertThat(this.service.nearest(new GeoPoint(31.47, 74.40)).id()).isEqualTo("lhr");
        assertThat(this.service.nearest(new GeoPoint(31.13, 74.45)).id()).isEqualTo("lhr");
        assertThat(this.service.nearest(new GeoPoint(27.0, 70.0))).isNull();
        assertThat(this.service.pricingOf("khi").maxDistanceMeters()).isEqualTo(15_000);
        assertThat(this.service.pricingOf("off")).isNull();
        assertThat(this.service.pricingOf("lhr")).isNull();
        assertThat(this.service.list()).extracting(City::id).containsExactly("khi", "off", "lhr");
    }

    private static CityDocument document(String id, String name, double lat, double lng, boolean active, Long version) {
        return document(id, name, lat, lng, active, version, null);
    }

    private static CityDocument document(String id, String name, double lat, double lng, boolean active, Long version,
            CityDocument.Pricing pricing) {
        return new CityDocument(id, Map.of("en", name), lat, lng, active, pricing, Instant.EPOCH, "admin-1", version);
    }
}
