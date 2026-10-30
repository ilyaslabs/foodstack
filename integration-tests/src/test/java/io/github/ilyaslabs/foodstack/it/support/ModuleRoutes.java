package io.github.ilyaslabs.foodstack.it.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which module owns which public path: the same map the gateway uses (Caddyfile on one server,
 * HTTPRoutes on Kubernetes). A path no module owns fails the test, so the map stays complete.
 */
public final class ModuleRoutes {

    public static final Map<String, List<String>> PREFIXES;

    static {
        Map<String, List<String>> prefixes = new LinkedHashMap<>();
        prefixes.put("identity", List.of("/api/v1/auth", "/api/v1/me", "/api/v1/users", "/oauth2", "/login",
                "/logout", "/.well-known", "/userinfo", "/connect"));
        prefixes.put("settings", List.of("/api/v1/settings"));
        prefixes.put("media", List.of("/api/v1/media"));
        PREFIXES = Map.copyOf(prefixes);
    }

    private ModuleRoutes() {
    }

    public static String moduleOf(String path) {
        return PREFIXES.entrySet().stream()
            .filter(entry -> entry.getValue().stream().anyMatch(prefix -> path.equals(prefix) || path.startsWith(prefix + "/")
                    || path.startsWith(prefix + "?")))
            .map(Map.Entry::getKey)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No module owns the path " + path));
    }
}
