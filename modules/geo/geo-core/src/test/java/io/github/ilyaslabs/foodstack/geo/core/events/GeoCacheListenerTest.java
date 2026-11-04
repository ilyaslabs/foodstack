package io.github.ilyaslabs.foodstack.geo.core.events;

import io.github.ilyaslabs.foodstack.geo.core.application.GeoCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class GeoCacheListenerTest {

    private final GeoCache cache = mock(GeoCache.class);

    private final GeoCacheListener listener = new GeoCacheListener(this.cache, JsonMapper.builder().build());

    @Test
    @DisplayName("evicts the zones or the pricing that a geo event says changed")
    void evicts() {
        this.listener.onEvent(event("foodstack.geo.ZoneChanged.v1", "{\"storeId\":\"s3\",\"zoneCount\":2,\"changedBy\":\"u\"}"));
        this.listener.onEvent(event("foodstack.geo.OperatorDeliveryPricingChanged.v1", "{\"version\":3,\"changedBy\":\"u\"}"));
        this.listener.onEvent(event("foodstack.geo.Other.v1", "{}"));

        verify(this.cache).evictZones("s3");
        verify(this.cache).evictOperatorPricing();
        verifyNoMoreInteractions(this.cache);
    }

    private static String event(String type, String data) {
        return "{\"specversion\":\"1.0\",\"id\":\"e1\",\"source\":\"foodstack/geo\",\"type\":\"" + type
                + "\",\"subject\":\"x\",\"time\":\"2026-11-04T09:00:00Z\",\"datacontenttype\":\"application/json\",\"data\":" + data
                + "}";
    }
}
