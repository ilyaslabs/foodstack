package io.github.ilyaslabs.foodstack.geo.core.routing;

import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OsrmRoutingProviderTest {

    private final RestClient.Builder builder = RestClient.builder();

    private final MockRestServiceServer server = MockRestServiceServer.bindTo(this.builder).build();

    private final OsrmRoutingProvider provider = new OsrmRoutingProvider(this.builder, "https://osrm.example", "driving");

    @Test
    @DisplayName("asks OSRM for the route in lng,lat order and reads distance and duration; no route is null")
    void route() {
        this.server.expect(requestTo("https://osrm.example/route/v1/driving/74.350000,31.520000;74.300000,31.500000"
                + "?overview=false&alternatives=false&steps=false"))
            .andRespond(withSuccess("""
                    {"code":"Ok","routes":[{"distance":6123.4,"duration":702.6}]}""", MediaType.APPLICATION_JSON));
        this.server.expect(requestTo("https://osrm.example/route/v1/driving/0.000000,0.000000;1.000000,1.000000"
                + "?overview=false&alternatives=false&steps=false"))
            .andRespond(withSuccess("{\"code\":\"NoRoute\",\"routes\":[]}", MediaType.APPLICATION_JSON));

        assertThat(this.provider.route(new GeoPoint(31.52, 74.35), new GeoPoint(31.5, 74.3)))
            .isEqualTo(new RoutingProvider.Route(6123, 703));
        assertThat(this.provider.route(new GeoPoint(0, 0), new GeoPoint(1, 1))).isNull();
    }
}
