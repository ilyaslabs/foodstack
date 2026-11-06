package io.github.ilyaslabs.foodstack.geo.api.events;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract snapshots: removing or renaming a field breaks consumers and fails here. */
class GeoEventsTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    @DisplayName("ZoneChanged matches its committed v1 snapshot and is keyed by branch")
    void zoneChanged() throws IOException {
        assertSnapshot("/events/ZoneChanged.v1.json", new ZoneChanged("store-1", 2, "user-1"), ZoneChanged.class);
        assertThat(new ZoneChanged("store-1", 2, "user-1").subject()).isEqualTo("store-1");
    }

    @Test
    @DisplayName("OperatorDeliveryPricingChanged matches its committed v1 snapshot")
    void pricingChanged() throws IOException {
        assertSnapshot("/events/OperatorDeliveryPricingChanged.v1.json", new OperatorDeliveryPricingChanged(4, "user-1"),
                OperatorDeliveryPricingChanged.class);
    }

    @Test
    @DisplayName("CityChanged matches its committed v1 snapshot and is keyed by city")
    void cityChanged() throws IOException {
        assertSnapshot("/events/CityChanged.v1.json", new CityChanged("city-1", 3, "user-1"), CityChanged.class);
        assertThat(new CityChanged("city-1", 3, "user-1").subject()).isEqualTo("city-1");
    }

    private <T> void assertSnapshot(String path, T event, Class<T> type) throws IOException {
        String snapshot = read(path);
        assertThat(this.jsonMapper.readTree(this.jsonMapper.writeValueAsString(event)))
            .isEqualTo(this.jsonMapper.readTree(snapshot));
        assertThat(this.jsonMapper.readValue(snapshot, type)).isEqualTo(event);
    }

    private static String read(String path) throws IOException {
        try (InputStream in = GeoEventsTest.class.getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
