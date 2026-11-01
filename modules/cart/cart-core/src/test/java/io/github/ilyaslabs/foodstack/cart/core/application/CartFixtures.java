package io.github.ilyaslabs.foodstack.cart.core.application;

import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.ilyaslabs.foodstack.geo.api.DeliveryMode;
import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import io.github.ilyaslabs.foodstack.store.api.OrderValidationView.LineProblem;
import io.github.ilyaslabs.foodstack.store.api.OrderValidationView.PricedLine;
import io.github.ilyaslabs.foodstack.store.api.OrderValidationView;

/** Store answers for cart tests. */
final class CartFixtures {

    private CartFixtures() {
    }

    static OrderValidationView.StoreSnapshot store(String storeId, boolean live, boolean open, boolean pickup) {
        return new OrderValidationView.StoreSnapshot(storeId, "spice-route-gulberg", "b1", "Spice Route", "Gulberg", Map.of(),
                new GeoPoint(31.52, 74.35), "1 Main Boulevard, Gulberg, Lahore", "Lahore", DeliveryMode.SELF, pickup, live, open,
                20, 1500, 0, Set.of("owner-1"), "Asia/Karachi");
    }

    static OrderValidationView priced(String storeId, long... lineTotals) {
        return priced(store(storeId, true, true, true), List.of(), lineTotals);
    }

    static OrderValidationView priced(OrderValidationView.StoreSnapshot store, List<LineProblem> problems, long... lineTotals) {
        List<PricedLine> lines = java.util.Arrays.stream(lineTotals)
            .mapToObj((total) -> new PricedLine("i1", Map.of("en", "Biryani"), 1, total, List.of(), total))
            .toList();
        return new OrderValidationView(store, "PKR", lines, java.util.Arrays.stream(lineTotals).sum(), problems);
    }
}
