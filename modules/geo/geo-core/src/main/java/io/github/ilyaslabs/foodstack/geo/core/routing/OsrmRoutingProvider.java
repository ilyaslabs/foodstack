package io.github.ilyaslabs.foodstack.geo.core.routing;

import java.util.Locale;

import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import tools.jackson.databind.JsonNode;

import org.springframework.web.client.RestClient;

/**
 * OSRM's {@code route} service (OpenStreetMap). Self-host it for the operator's region or use a
 * hosted provider; the public demo server is not for production (deployment guide).
 */
public class OsrmRoutingProvider implements RoutingProvider {

    private final RestClient client;

    private final String profile;

    public OsrmRoutingProvider(RestClient.Builder builder, String url, String profile) {
        this.client = builder.baseUrl(url).build();
        this.profile = (profile == null || profile.isBlank()) ? "driving" : profile.strip();
    }

    @Override
    public Route route(GeoPoint from, GeoPoint to) {
        String coordinates = String.format(Locale.ROOT, "%.6f,%.6f;%.6f,%.6f", from.lng(), from.lat(), to.lng(), to.lat());
        JsonNode body = this.client.get()
            .uri("/route/v1/" + this.profile + "/" + coordinates + "?overview=false&alternatives=false&steps=false")
            .retrieve()
            .body(JsonNode.class);
        if (body == null || !"Ok".equals(body.path("code").asString()) || body.path("routes").isEmpty()) {
            return null;
        }
        JsonNode route = body.path("routes").get(0);
        return new Route(Math.round(route.path("distance").asDouble()), Math.round(route.path("duration").asDouble()));
    }
}
