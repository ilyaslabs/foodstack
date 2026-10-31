package io.github.ilyaslabs.foodstack.platform.security;

import java.util.List;

import org.springframework.http.HttpMethod;

/**
 * Endpoints under {@code /api/**} that need no token, declared by the module that owns them, for
 * example {@code PublicEndpoints.of(GET, "/api/v1/settings/branding")}. A module declares them in
 * its {@code -web} configuration, so the list follows the module wherever it runs.
 */
public record PublicEndpoints(List<Endpoint> endpoints) {

    public static PublicEndpoints of(HttpMethod method, String... patterns) {
        return new PublicEndpoints(java.util.Arrays.stream(patterns).map((p) -> new Endpoint(method, p)).toList());
    }

    public PublicEndpoints and(HttpMethod method, String... patterns) {
        List<Endpoint> all = new java.util.ArrayList<>(this.endpoints);
        java.util.Arrays.stream(patterns).forEach((p) -> all.add(new Endpoint(method, p)));
        return new PublicEndpoints(List.copyOf(all));
    }

    public record Endpoint(HttpMethod method, String pattern) {
    }
}
