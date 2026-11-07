package io.github.ilyaslabs.foodstack.search.core.events;

import io.github.ilyaslabs.foodstack.search.core.application.StoreIndexer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class CatalogueEventsListenerTest {

    private final StoreIndexer indexer = mock(StoreIndexer.class);

    private final CatalogueEventsListener listener = new CatalogueEventsListener(this.indexer, JsonMapper.builder().build());

    @Test
    @DisplayName("routes store, geo and review events to the right refresh by their CloudEvents type")
    void routes() {
        this.listener.onEvent(event("foodstack.store.StoreChanged.v1", "{\"storeId\":\"s1\",\"brandId\":\"b1\",\"version\":2,\"changedBy\":\"u\"}"));
        this.listener.onEvent(event("foodstack.store.MenuChanged.v1", "{\"brandId\":\"b1\",\"changedBy\":\"u\"}"));
        this.listener.onEvent(event("foodstack.store.ItemAvailabilityChanged.v1",
                "{\"storeId\":\"s2\",\"itemId\":\"i1\",\"optionId\":null,\"soldOut\":true,\"changedBy\":\"u\"}"));
        this.listener.onEvent(event("foodstack.geo.ZoneChanged.v1", "{\"storeId\":\"s3\",\"zoneCount\":2,\"changedBy\":\"u\"}"));
        this.listener.onEvent(event("foodstack.geo.OperatorDeliveryPricingChanged.v1", "{\"version\":3,\"changedBy\":\"u\"}"));
        this.listener.onEvent(event("foodstack.review.ReviewPublished.v1", "{\"reviewId\":\"r1\",\"storeId\":\"s4\",\"rating\":5,\"flagged\":false,\"count\":1,\"average\":5.0}"));
        this.listener.onEvent(event("foodstack.review.ReviewHidden.v1", "{\"reviewId\":\"r2\",\"storeId\":\"s5\",\"count\":0,\"average\":0}"));

        verify(this.indexer).refresh("s1");
        verify(this.indexer).refreshBrand("b1");
        verify(this.indexer).refresh("s2");
        verify(this.indexer).refresh("s3");
        verify(this.indexer).refreshOperatorDelivery();
        verify(this.indexer).refresh("s4");
        verify(this.indexer).refresh("s5");
    }

    @Test
    @DisplayName("ignores events it does not use")
    void ignores() {
        this.listener.onEvent(event("foodstack.media.MediaReady.v1", "{\"mediaId\":\"m1\"}"));
        verifyNoInteractions(this.indexer);
    }

    private static String event(String type, String data) {
        return "{\"specversion\":\"1.0\",\"id\":\"e1\",\"source\":\"foodstack/x\",\"type\":\"" + type
                + "\",\"subject\":\"x\",\"time\":\"2026-10-31T09:00:00Z\",\"datacontenttype\":\"application/json\",\"data\":" + data
                + "}";
    }
}
