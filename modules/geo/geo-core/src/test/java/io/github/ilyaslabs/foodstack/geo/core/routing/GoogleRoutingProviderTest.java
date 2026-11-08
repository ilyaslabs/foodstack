package io.github.ilyaslabs.foodstack.geo.core.routing;

import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GoogleRoutingProviderTest {

    private final RestClient.Builder builder = RestClient.builder();

    private final MockRestServiceServer server = MockRestServiceServer.bindTo(this.builder).build();

    private final GoogleRoutingProvider provider = new GoogleRoutingProvider(this.builder, "https://routes.example",
            "test-key", "two_wheeler");

    @Test
    @DisplayName("asks the Routes API with the key and field mask, without traffic, and reads distance and duration")
    void route() {
        this.server.expect(requestTo("https://routes.example/directions/v2:computeRoutes"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("X-Goog-Api-Key", "test-key"))
            .andExpect(header("X-Goog-FieldMask", "routes.distanceMeters,routes.duration"))
            .andExpect(content().json("""
                    {"origin":{"location":{"latLng":{"latitude":31.52,"longitude":74.35}}},
                     "destination":{"location":{"latLng":{"latitude":31.5,"longitude":74.3}}},
                     "travelMode":"TWO_WHEELER","routingPreference":"TRAFFIC_UNAWARE"}"""))
            .andRespond(withSuccess("{\"routes\":[{\"distanceMeters\":6123,\"duration\":\"702.6s\"}]}",
                    MediaType.APPLICATION_JSON));
        this.server.expect(requestTo("https://routes.example/directions/v2:computeRoutes"))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(this.provider.route(new GeoPoint(31.52, 74.35), new GeoPoint(31.5, 74.3)))
            .isEqualTo(new RoutingProvider.Route(6123, 703));
        assertThat(this.provider.route(new GeoPoint(0, 0), new GeoPoint(1, 1))).isNull();
    }

    @Test
    @DisplayName("OSRM profile names map to Google travel modes")
    void travelModes() {
        assertThat(GoogleRoutingProvider.travelMode(null)).isEqualTo("DRIVE");
        assertThat(GoogleRoutingProvider.travelMode("driving")).isEqualTo("DRIVE");
        assertThat(GoogleRoutingProvider.travelMode("motorbike")).isEqualTo("TWO_WHEELER");
        assertThat(GoogleRoutingProvider.travelMode("cycling")).isEqualTo("BICYCLE");
    }
}
