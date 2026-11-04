package io.github.ilyaslabs.foodstack.settings.api;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * Keeps each settings answer for a short time, for callers that reach the settings module over the
 * network (TDD 7.5, gap 12d). Settings change rarely, and a branch page or checkout must not cost a
 * call to another service each time it asks for the currency or time zone.
 */
public final class CachingSettingsApi implements SettingsApi {

    private final SettingsApi delegate;

    private final Duration ttl;

    private final Clock clock;

    private final Kept<BrandingView> branding = new Kept<>();

    private final Kept<LocaleView> locale = new Kept<>();

    private final Kept<OrderingView> ordering = new Kept<>();

    private final Kept<DeliveryView> delivery = new Kept<>();

    public CachingSettingsApi(SettingsApi delegate, Duration ttl, Clock clock) {
        this.delegate = delegate;
        this.ttl = ttl;
        this.clock = clock;
    }

    @Override
    public BrandingView getBranding() {
        return this.branding.get(this.delegate::getBranding);
    }

    @Override
    public LocaleView getLocale() {
        return this.locale.get(this.delegate::getLocale);
    }

    @Override
    public OrderingView getOrdering() {
        return this.ordering.get(this.delegate::getOrdering);
    }

    @Override
    public DeliveryView getDelivery() {
        return this.delivery.get(this.delegate::getDelivery);
    }

    private final class Kept<T> {

        private volatile T value;

        private volatile Instant until = Instant.MIN;

        T get(Supplier<T> loader) {
            Instant now = CachingSettingsApi.this.clock.instant();
            T current = this.value;
            if (current != null && now.isBefore(this.until)) {
                return current;
            }
            T fresh = loader.get();
            this.value = fresh;
            this.until = now.plus(CachingSettingsApi.this.ttl);
            return fresh;
        }
    }
}
