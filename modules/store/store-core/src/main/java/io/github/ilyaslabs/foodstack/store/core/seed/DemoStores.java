package io.github.ilyaslabs.foodstack.store.core.seed;

/**
 * Fixed ids of the demo restaurants of the {@code test-seed} profile, so the geo seeder can give
 * them delivery zones without calling the store module.
 */
public final class DemoStores {

    public static final String SPICE_ROUTE_BRAND = "0195f0a0-0000-7000-8000-000000000001";

    public static final String SPICE_ROUTE_GULBERG = "0195f0a0-0000-7000-8000-000000000101";

    public static final String NAPOLI_BRAND = "0195f0a0-0000-7000-8000-000000000002";

    public static final String NAPOLI_DHA = "0195f0a0-0000-7000-8000-000000000201";

    private DemoStores() {
    }
}
