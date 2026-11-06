package io.github.ilyaslabs.foodstack.search.core.application;

import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.geo.api.DeliveryAreaRequest;
import io.github.ilyaslabs.foodstack.geo.api.DeliveryAreaView;
import io.github.ilyaslabs.foodstack.geo.api.DeliveryMode;
import io.github.ilyaslabs.foodstack.geo.api.GeoApi;
import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import io.github.ilyaslabs.foodstack.search.core.engine.IndexedStore;
import io.github.ilyaslabs.foodstack.search.core.engine.StoreSearchEngine;
import io.github.ilyaslabs.foodstack.store.api.StoreApi;
import io.github.ilyaslabs.foodstack.store.api.StoreIdPage;
import io.github.ilyaslabs.foodstack.store.api.StoreListingView;
import io.github.ilyaslabs.foodstack.store.api.StoreStatus;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class StoreIndexerTest {

    private static final GeoPoint LOCATION = new GeoPoint(31.52, 74.35);

    private final StoreApi stores = mock(StoreApi.class);

    private final GeoApi geo = mock(GeoApi.class);

    private final StoreSearchEngine engine = mock(StoreSearchEngine.class);

    private final StoreIndexer indexer = new StoreIndexer(this.stores, this.geo, this.engine, passThrough());

    /** Runs the work directly, as the platform would with its service token. */
    @SuppressWarnings("unchecked")
    private static io.github.ilyaslabs.foodstack.platform.security.ServiceCalls passThrough() {
        io.github.ilyaslabs.foodstack.platform.security.ServiceCalls calls = mock(
                io.github.ilyaslabs.foodstack.platform.security.ServiceCalls.class);
        org.mockito.Mockito.doAnswer((invocation) -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(calls).run(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.when(calls.call(org.mockito.ArgumentMatchers.any()))
            .thenAnswer((invocation) -> ((java.util.function.Supplier<Object>) invocation.getArgument(0)).get());
        return calls;
    }

    @Test
    @DisplayName("indexes a live branch with its delivery area from geo")
    void live() {
        List<List<GeoPoint>> area = List.of(List.of(new GeoPoint(31.5, 74.3), new GeoPoint(31.5, 74.4), new GeoPoint(31.6, 74.35)));
        given(this.stores.getListing("s1")).willReturn(listing("s1", StoreStatus.LIVE));
        given(this.geo.deliveryArea(new DeliveryAreaRequest("s1", LOCATION, DeliveryMode.SELF)))
            .willReturn(new DeliveryAreaView("s1", area));

        this.indexer.refresh("s1");

        ArgumentCaptor<IndexedStore> indexed = ArgumentCaptor.forClass(IndexedStore.class);
        verify(this.engine).index(indexed.capture());
        assertThat(indexed.getValue().deliveryArea()).isEqualTo(area);
        assertThat(indexed.getValue().dishNames()).containsEntry("ar", List.of("برياني"));
        assertThat(indexed.getValue().deliveryMode()).isEqualTo("SELF");
    }

    @Test
    @DisplayName("removes branches that are not live, and branches that no longer exist")
    void notLive() {
        given(this.stores.getListing("s1")).willReturn(listing("s1", StoreStatus.PAUSED));
        willThrow(new ModuleException.NotFound("gone", "STORE_NOT_FOUND")).given(this.stores).getListing("s2");

        this.indexer.refresh("s1");
        this.indexer.refresh("s2");

        verify(this.engine).delete("s1");
        verify(this.engine).delete("s2");
        verify(this.engine, never()).index(any());
        verify(this.geo, never()).deliveryArea(any());
    }

    @Test
    @DisplayName("refreshes every indexed branch of a brand, or of operator delivery")
    void fanOut() {
        given(this.engine.storeIdsOfBrand("b1")).willReturn(List.of("s1", "s2"));
        given(this.engine.storeIdsWithDeliveryMode("OPERATOR")).willReturn(List.of("s3"));
        given(this.stores.getListing(any())).willAnswer((invocation) -> listing(invocation.getArgument(0), StoreStatus.LIVE));
        given(this.geo.deliveryArea(any())).willReturn(new DeliveryAreaView("x", List.of()));

        this.indexer.refreshBrand("b1");
        this.indexer.refreshOperatorDelivery();

        verify(this.engine, times(3)).index(any());
    }

    @Test
    @DisplayName("a rebuild pages through every branch and keeps going when one fails")
    void rebuild() {
        given(this.stores.listStoreIds(null, 200)).willReturn(new StoreIdPage(List.of("s1", "s2"), "s2"));
        given(this.stores.listStoreIds("s2", 200)).willReturn(new StoreIdPage(List.of("s3"), null));
        given(this.stores.getListing(any())).willAnswer((invocation) -> listing(invocation.getArgument(0), StoreStatus.LIVE));
        given(this.geo.deliveryArea(any())).willReturn(new DeliveryAreaView("x", List.of()));
        willThrow(new ModuleException.Unavailable("down", "SEARCH_UNAVAILABLE")).given(this.engine)
            .index(org.mockito.ArgumentMatchers.argThat((IndexedStore s) -> s != null && s.storeId().equals("s2")));

        assertThat(this.indexer.rebuild()).isEqualTo(3);
        verify(this.engine, times(3)).index(any());
    }

    private static StoreListingView listing(String storeId, StoreStatus status) {
        return new StoreListingView(storeId, "spice-route-gulberg", "b1", "Spice Route", "Gulberg", 1, Map.of("en", "Biryani"), List.of("pakistani"),
                Map.of(), Map.of(), LOCATION, "Gulberg", "Lahore", DeliveryMode.SELF, true, status, false, 25, "Asia/Karachi",
                Map.of(), List.of(), Map.of("en", List.of("Biryani"), "ar", List.of("برياني")), List.of("halal"), 4, null);
    }
}
