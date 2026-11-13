package io.github.ilyaslabs.foodstack.geo.core;

import io.github.ilyaslabs.foodstack.geo.core.geocoding.NominatimGeocodingProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeocodingProviderChoiceTest {

    private final GeoConfiguration configuration = new GeoConfiguration();

    @Test
    @DisplayName("production refuses OpenStreetMap's public server unless it is allowed")
    void refusesPublicServerInProduction() {
        GeoProperties properties = new GeoProperties();
        properties.getGeocoding().setUrl("https://nominatim.openstreetmap.org");

        assertThatThrownBy(() -> this.configuration.geoGeocodingProvider(properties, "production"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("foodstack.geo.geocoding.url");
        assertThat(this.configuration.geoGeocodingProvider(properties, "development"))
            .isInstanceOf(NominatimGeocodingProvider.class);
        properties.getGeocoding().setAllowPublicServer(true);
        assertThat(this.configuration.geoGeocodingProvider(properties, "production"))
            .isInstanceOf(NominatimGeocodingProvider.class);
    }

    @Test
    @DisplayName("production accepts any other Nominatim-compatible server")
    void acceptsHostedServer() {
        GeoProperties properties = new GeoProperties();
        properties.getGeocoding().setUrl("https://search.example-provider.com");

        assertThat(this.configuration.geoGeocodingProvider(properties, "production"))
            .isInstanceOf(NominatimGeocodingProvider.class);
    }

}
