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
        prefixes.put("identity", List.of("/api/v1/auth", "/api/v1/me", "/api/v1/users", "/oauth2", "/.well-known",
                "/api/v1/audit"));
        prefixes.put("settings", List.of("/api/v1/settings"));
        prefixes.put("media", List.of("/api/v1/media"));
        prefixes.put("geo", List.of("/api/v1/geo", "/api/v1/zones"));
        prefixes.put("store", List.of("/api/v1/stores", "/api/v1/brands"));
        prefixes.put("search", List.of("/api/v1/search"));
        prefixes.put("promotion", List.of("/api/v1/promotions", "/api/v1/coupons"));
        prefixes.put("cart", List.of("/api/v1/carts"));
        prefixes.put("payment", List.of("/api/v1/payments", "/api/v1/payment-webhooks"));
        prefixes.put("order", List.of("/api/v1/orders"));
        prefixes.put("rider", List.of("/api/v1/riders", "/api/v1/rider"));
        prefixes.put("dispatch", List.of("/api/v1/deliveries", "/api/v1/dispatch"));
        prefixes.put("notification", List.of("/api/v1/notifications", "/api/v1/devices"));
        prefixes.put("ledger", List.of("/api/v1/ledger", "/api/v1/payouts"));
        prefixes.put("review", List.of("/api/v1/reviews"));
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
