package io.github.ilyaslabs.foodstack.geo.core.geocoding;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import io.github.ilyaslabs.foodstack.geo.core.application.AddressSuggestion;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Google's Geocoding API for address search and the address at a map pin. Results are limited to
 * the deployment's country ({@code country-codes}, first code; one deployment serves one country).
 */
public class GoogleGeocodingProvider implements GeocodingProvider {

    /** Google's endpoint; tests point the client elsewhere. */
    public static final String URL = "https://maps.googleapis.com";

    private static final Logger log = LoggerFactory.getLogger(GoogleGeocodingProvider.class);

    private final RestClient client;

    private final String apiKey;

    private final String country;

    public GoogleGeocodingProvider(RestClient.Builder builder, String url, String apiKey, String countryCodes) {
        this.client = builder.baseUrl((url == null || url.isBlank()) ? URL : url).build();
        this.apiKey = apiKey;
        String codes = (countryCodes != null) ? countryCodes.strip() : "";
        this.country = codes.isEmpty() ? "" : codes.split(",")[0].strip().toUpperCase(Locale.ROOT);
    }

    @Override
    public List<AddressSuggestion> search(String query, String language) {
        JsonNode body = call(() -> this.client.get()
            .uri((uri) -> {
                uri.path("/maps/api/geocode/json").queryParam("address", query).queryParam("language", language);
                if (!this.country.isEmpty()) {
                    uri.queryParam("components", "country:" + this.country);
                }
                return uri.queryParam("key", this.apiKey).build();
            })
            .retrieve()
            .body(JsonNode.class));
        return suggestions(body, 5);
    }

    @Override
    public List<AddressSuggestion> reverse(double lat, double lng, String language) {
        JsonNode body = call(() -> this.client.get()
            .uri((uri) -> uri.path("/maps/api/geocode/json")
                .queryParam("latlng", String.format(Locale.ROOT, "%.6f,%.6f", lat, lng))
                .queryParam("language", language)
                .queryParam("key", this.apiKey)
                .build())
            .retrieve()
            .body(JsonNode.class));
        return suggestions(body, 1);
    }

    private static List<AddressSuggestion> suggestions(JsonNode body, int limit) {
        String status = (body != null) ? body.path("status").asString() : "";
        if ("ZERO_RESULTS".equals(status)) {
            return List.of();
        }
        if (!"OK".equals(status)) {
            // REQUEST_DENIED (key missing, wrong or without the Geocoding API), OVER_QUERY_LIMIT, ...
            log.warn("Google geocoding answered {}: {}", status, (body != null) ? body.path("error_message").asString() : "");
            throw unavailable();
        }
        List<AddressSuggestion> found = new ArrayList<>();
        for (JsonNode result : body.path("results")) {
            if (found.size() == limit) {
                break;
            }
            JsonNode location = result.path("geometry").path("location");
            found.add(new AddressSuggestion(result.path("formatted_address").asString(), location.path("lat").asDouble(),
                    location.path("lng").asDouble()));
        }
        return found;
    }

    private static JsonNode call(Supplier<JsonNode> request) {
        try {
            return request.get();
        }
        catch (RestClientException ex) {
            throw unavailable();
        }
    }

    private static ModuleException unavailable() {
        return new ModuleException.Unavailable("Address search is not reachable right now", "GEOCODING_UNAVAILABLE");
    }
}
