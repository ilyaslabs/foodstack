package io.github.ilyaslabs.foodstack.geo.core.geocoding;

import java.util.ArrayList;
import java.util.List;

import io.github.ilyaslabs.foodstack.geo.core.application.AddressSuggestion;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Nominatim (OpenStreetMap) search and reverse lookup. Self-host it or use a hosted provider in
 * production: the public server allows one request per second (deployment guide).
 */
public class NominatimGeocodingProvider implements GeocodingProvider {

    private final RestClient client;

    private final String countryCodes;

    public NominatimGeocodingProvider(RestClient.Builder builder, String url, String userAgent, String countryCodes) {
        this.client = builder.baseUrl(url).defaultHeader(HttpHeaders.USER_AGENT, userAgent).build();
        this.countryCodes = (countryCodes != null) ? countryCodes.trim() : "";
    }

    @Override
    public List<AddressSuggestion> search(String query, String language) {
        JsonNode results = call(() -> this.client.get()
            .uri((uri) -> {
                uri.path("/search").queryParam("format", "jsonv2").queryParam("limit", 5).queryParam("q", query);
                if (!this.countryCodes.isEmpty()) {
                    uri.queryParam("countrycodes", this.countryCodes);
                }
                return uri.build();
            })
            .header(HttpHeaders.ACCEPT_LANGUAGE, language)
            .retrieve()
            .body(JsonNode.class));
        List<AddressSuggestion> suggestions = new ArrayList<>();
        if (results != null) {
            results.forEach((result) -> suggestions.add(toSuggestion(result)));
        }
        return suggestions;
    }

    @Override
    public List<AddressSuggestion> reverse(double lat, double lng, String language) {
        JsonNode result = call(() -> this.client.get()
            .uri((uri) -> uri.path("/reverse").queryParam("format", "jsonv2").queryParam("lat", lat)
                .queryParam("lon", lng).build())
            .header(HttpHeaders.ACCEPT_LANGUAGE, language)
            .retrieve()
            .body(JsonNode.class));
        if (result == null || result.has("error") || !result.has("display_name")) {
            return List.of();
        }
        return List.of(toSuggestion(result));
    }

    private static AddressSuggestion toSuggestion(JsonNode node) {
        return new AddressSuggestion(node.path("display_name").asString(), Double.parseDouble(node.path("lat").asString()),
                Double.parseDouble(node.path("lon").asString()));
    }

    private static JsonNode call(java.util.function.Supplier<JsonNode> request) {
        try {
            return request.get();
        }
        catch (RestClientException ex) {
            throw new ModuleException.Unavailable("Address search is not reachable right now", "GEOCODING_UNAVAILABLE");
        }
    }
}
