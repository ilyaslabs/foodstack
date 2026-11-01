package io.github.ilyaslabs.foodstack.settings.impl;

import io.github.ilyaslabs.foodstack.platform.core.EpochSeconds;
import io.github.ilyaslabs.foodstack.settings.api.BrandingView;
import io.github.ilyaslabs.foodstack.settings.api.LocaleView;
import io.github.ilyaslabs.foodstack.settings.api.OrderingView;
import io.github.ilyaslabs.foodstack.settings.api.SettingsApi;
import io.github.ilyaslabs.foodstack.settings.core.application.BrandingDetails;
import io.github.ilyaslabs.foodstack.settings.core.application.LocaleDetails;
import io.github.ilyaslabs.foodstack.settings.core.application.OrderingDetails;
import io.github.ilyaslabs.foodstack.settings.core.application.OrderingSettingsService;
import io.github.ilyaslabs.foodstack.settings.core.application.SettingsService;

import org.springframework.stereotype.Component;

/**
 * {@link SettingsApi} for other modules: delegates to the core service and maps the result. Open to
 * every caller: branding, languages and ordering rules are public (TDD 9.1).
 */
@Component
class SettingsApiImpl implements SettingsApi {

    private final SettingsService settingsService;

    private final OrderingSettingsService orderingService;

    SettingsApiImpl(SettingsService settingsService, OrderingSettingsService orderingService) {
        this.settingsService = settingsService;
        this.orderingService = orderingService;
    }

    @Override
    public BrandingView getBranding() {
        BrandingDetails b = this.settingsService.getBranding();
        return new BrandingView(b.name(), b.tagline(), b.primaryColor(), b.secondaryColor(), b.fontFamily(),
                b.logoMediaId(), b.logoUrls(), b.version(), EpochSeconds.of(b.updatedAt()));
    }

    @Override
    public LocaleView getLocale() {
        LocaleDetails l = this.settingsService.getLocale();
        return new LocaleView(l.defaultLanguage(), l.languages(), l.currency(), l.timezone(), l.version());
    }

    @Override
    public OrderingView getOrdering() {
        OrderingDetails o = this.orderingService.get();
        return new OrderingView(o.cashEnabled(), o.cardEnabled(), o.serviceFeeMinor(), o.serviceFeeBasisPoints(),
                o.taxBasisPoints(), o.tipsEnabled(), o.acceptTimeoutSeconds(), o.version());
    }
}
