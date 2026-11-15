package io.github.ilyaslabs.foodstack.settings.api;

import io.github.ilyaslabs.evl.EVL;

/** What other modules may ask the settings module. Both answers are cached and cheap to call. */
@EVL.Interface(value = "SettingsApi", description = "The settings module API: an in-process call, or HTTP when settings runs as its own service")
public interface SettingsApi {

    /** The operator's branding (name, colours, logo). */
    BrandingView getBranding();

    /** Languages, currency and timezone of the deployment (one of each per deployment, BRD). */
    LocaleView getLocale();

    /** Payment methods, fees, tax, tips and the store accept time. */
    OrderingView getOrdering();

    /** Rider pay, dispatch timings and proof of delivery (PRD 4.6). */
    DeliveryView getDelivery();
}
