package io.github.ilyaslabs.foodstack.geo.core.routing;

import java.util.Locale;
import java.util.Map;

import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import tools.jackson.databind.JsonNode;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Google's Routes API ({@code computeRoutes}): road distance and driving time without traffic, so
 * the same trip quotes the same price all day.
 */
public class GoogleRoutingProvider implements RoutingProvider {

    /** Google's endpoint; tests point the client elsewhere. */
    public static final String URL = "https://routes.googleapis.com";

    private final RestClient client;

    private final String travelMode;

    public GoogleRoutingProvider(RestClient.Builder builder, String url, String apiKey, String profile) {
        this.client = builder.baseUrl((url == null || url.isBlank()) ? URL : url)
            .defaultHeader("X-Goog-Api-Key", apiKey)
            .defaultHeader("X-Goog-FieldMask", "routes.distanceMeters,routes.duration")
            .build();
        this.travelMode = travelMode(profile);
    }

    /** OSRM-style profile names, or Google's own travel mode. */
    static String travelMode(String profile) {
        String value = (profile == null || profile.isBlank()) ? "driving" : profile.strip().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "driving", "car", "drive" -> "DRIVE";
            case "two_wheeler", "motorbike", "motorcycle", "scooter" -> "TWO_WHEELER";
            case "cycling", "bicycle", "bike" -> "BICYCLE";
            default -> value.toUpperCase(Locale.ROOT);
        };
    }

    @Override
    public Route route(GeoPoint from, GeoPoint to) {
        Map<String, Object> body = Map.of("origin", waypoint(from), "destination", waypoint(to), "travelMode",
                this.travelMode, "routingPreference", "TRAFFIC_UNAWARE");
        JsonNode answer = this.client.post()
            .uri("/directions/v2:computeRoutes")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(JsonNode.class);
        if (answer == null || answer.path("routes").isEmpty()) {
            return null;
        }
        JsonNode route = answer.path("routes").get(0);
        String duration = route.path("duration").asString("0s");
        long seconds = Math.round(Double.parseDouble(duration.endsWith("s") ? duration.substring(0, duration.length() - 1)
                : duration));
        return new Route(route.path("distanceMeters").asLong(), seconds);
    }

    private static Map<String, Object> waypoint(GeoPoint point) {
        return Map.of("location", Map.of("latLng", Map.of("latitude", point.lat(), "longitude", point.lng())));
    }
}
