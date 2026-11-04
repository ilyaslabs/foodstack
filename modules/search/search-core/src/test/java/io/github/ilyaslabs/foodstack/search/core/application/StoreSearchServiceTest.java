package io.github.ilyaslabs.foodstack.search.core.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import io.github.ilyaslabs.foodstack.search.api.CuisineCount;
import io.github.ilyaslabs.foodstack.search.api.Fulfilment;
import io.github.ilyaslabs.foodstack.search.api.StoreCard;
import io.github.ilyaslabs.foodstack.search.api.StoreSearchPage;
import io.github.ilyaslabs.foodstack.search.api.StoreSearchQuery;
import io.github.ilyaslabs.foodstack.search.core.engine.IndexedStore;
import io.github.ilyaslabs.foodstack.search.core.engine.StoreSearchEngine;
import io.github.ilyaslabs.foodstack.store.api.TimeRange;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class StoreSearchServiceTest {

    // Saturday 14:00 in Lahore
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-31T09:00:00Z"), ZoneOffset.UTC);

    private final StoreSearchEngine engine = mock(StoreSearchEngine.class);

    private final StoreSearchService service = new StoreSearchService(this.engine, this.clock);

    @Test
    @DisplayName("cleans the query, keeps the engine order (it ranks open branches first) and marks which are open")
    void search() {
        given(this.engine.search(new StoreSearchQuery(31.5, 74.3, "biryani", "bbq", Fulfilment.DELIVERY, 0, 50, 8000)))
            .willReturn(new StoreSearchEngine.Result(List.of(hit("closed-near", "18:00", "23:00", false, 500),
                    hit("open-far", "12:00", "23:00", false, 3000), hit("switched-off", "12:00", "23:00", true, 4000),
                    hit("open-farther", "12:00", "15:00", false, 5000)),
                    List.of(new CuisineCount("bbq", 4)), 4));

        StoreSearchPage page = this.service.search(new StoreSearchQuery(31.5, 74.3, "  biryani ", " BBQ", null, -3, 500));

        assertThat(page.stores()).extracting(StoreCard::storeId)
            .containsExactly("closed-near", "open-far", "switched-off", "open-farther");
        assertThat(page.stores()).extracting(StoreCard::openNow).containsExactly(false, true, false, true);
        assertThat(page.stores().get(1).distanceMeters()).isEqualTo(3000);
        assertThat(page.size()).isEqualTo(50);
        assertThat(page.page()).isZero();
        assertThat(page.total()).isEqualTo(4);
        assertThat(page.radiusMeters()).isEqualTo(8000);
        assertThat(page.widerRadiusMeters()).isEqualTo(15_000);
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    @DisplayName("delivery search looks within the discovery steps, widening one step at a time; pickup has its own radius")
    void discoverySteps() {
        given(this.engine.search(any())).willReturn(new StoreSearchEngine.Result(List.of(), List.of(), 0));

        StoreSearchPage wider = this.service.search(new StoreSearchQuery(31.5, 74.3, null, null, Fulfilment.DELIVERY, 0, 20, 15_000));
        StoreSearchPage widest = this.service.search(new StoreSearchQuery(31.5, 74.3, null, null, Fulfilment.DELIVERY, 0, 20, 99_000));
        StoreSearchPage between = this.service.search(new StoreSearchQuery(31.5, 74.3, null, null, Fulfilment.DELIVERY, 0, 20, 9000));
        StoreSearchPage pickup = this.service.search(new StoreSearchQuery(31.5, 74.3, null, null, Fulfilment.PICKUP, 0, 20, 25_000));

        assertThat(wider.radiusMeters()).isEqualTo(15_000);
        assertThat(wider.widerRadiusMeters()).isEqualTo(25_000);
        assertThat(widest.radiusMeters()).isEqualTo(25_000);
        assertThat(widest.widerRadiusMeters()).isNull();
        assertThat(between.radiusMeters()).isEqualTo(15_000);
        assertThat(pickup.radiusMeters()).isEqualTo(15_000);
        assertThat(pickup.widerRadiusMeters()).isNull();
        verify(this.engine).search(new StoreSearchQuery(31.5, 74.3, null, null, Fulfilment.DELIVERY, 0, 20, 25_000));
        verify(this.engine).search(new StoreSearchQuery(31.5, 74.3, null, null, Fulfilment.PICKUP, 0, 20, null));
    }

    @Test
    @DisplayName("results stop at the configured depth: the last page is cut short and nothing is read beyond it")
    void depthCap() {
        given(this.engine.search(any(), org.mockito.ArgumentMatchers.anyInt()))
            .willReturn(new StoreSearchEngine.Result(List.of(hit("a", "12:00", "23:00", false, 1)), List.of(), 900));
        given(this.engine.search(any())).willReturn(new StoreSearchEngine.Result(
                java.util.Collections.nCopies(30, hit("b", "12:00", "23:00", false, 1)), List.of(), 900));

        StoreSearchPage full = this.service.search(new StoreSearchQuery(31.5, 74.3, null, null, Fulfilment.DELIVERY, 15, 30));
        StoreSearchPage last = this.service.search(new StoreSearchQuery(31.5, 74.3, null, null, Fulfilment.DELIVERY, 16, 30));
        StoreSearchPage beyond = this.service.search(new StoreSearchQuery(31.5, 74.3, null, null, Fulfilment.DELIVERY, 17, 30));

        assertThat(full.hasMore()).isTrue();
        verify(this.engine).search(new StoreSearchQuery(31.5, 74.3, null, null, Fulfilment.DELIVERY, 16, 20, 8000), 480);
        assertThat(last.hasMore()).isFalse();
        assertThat(beyond.stores()).isEmpty();
        assertThat(beyond.hasMore()).isFalse();
    }

    @Test
    @DisplayName("rejects points off the map and odd cuisines before asking the engine")
    void invalid() {
        assertThatThrownBy(() -> this.service.search(new StoreSearchQuery(95, 74.3, null, null, Fulfilment.DELIVERY, 0, 20)))
            .isInstanceOfSatisfying(ModuleException.BadRequest.class, (ex) -> assertThat(ex.code()).isEqualTo("LOCATION_INVALID"));
        assertThatThrownBy(() -> this.service.search(new StoreSearchQuery(31, 74, null, "<script>", Fulfilment.DELIVERY, 0, 20)))
            .isInstanceOfSatisfying(ModuleException.BadRequest.class, (ex) -> assertThat(ex.code()).isEqualTo("SEARCH_INVALID"));
        verify(this.engine, never()).search(any());
    }

    private static StoreSearchEngine.Hit hit(String id, String open, String close, boolean switchedOff, long distance) {
        Map<String, List<TimeRange>> hours = Map.of("SATURDAY", List.of(new TimeRange(open, close)));
        return new StoreSearchEngine.Hit(new IndexedStore(id, id, id, id, null, 1, Map.of(), List.of("bbq"), Map.of(), Map.of(),
                new GeoPoint(31.5, 74.3), null, "Lahore", "SELF", false, switchedOff, 20, "Asia/Karachi", hours, List.of(),
                Map.of(), List.of(), List.of(), 1), distance);
    }
}
