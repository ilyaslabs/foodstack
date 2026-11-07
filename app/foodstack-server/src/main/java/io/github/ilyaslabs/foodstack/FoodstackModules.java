package io.github.ilyaslabs.foodstack;

import java.util.List;

/** Every module this server can run, in dependency order. */
final class FoodstackModules {

    static final List<String> ALL = List.of("identity", "settings", "media", "geo", "store", "search", "promotion", "cart",
            "payment", "order", "rider", "dispatch", "notification", "ledger");

    private FoodstackModules() {
    }
}
