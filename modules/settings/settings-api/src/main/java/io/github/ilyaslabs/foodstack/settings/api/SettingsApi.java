package io.github.ilyaslabs.foodstack.settings.api;

import io.github.ilyaslabs.evl.EVL;

/** What other modules may ask the settings module. Both answers are cached and cheap to call. */
@EVL.Interface(id = "settings.api", name = "Settings API", module = "settings",
        responsibilities = { "Branding: name, colours, font and logo URLs",
                "Locale: languages, currency and time zone of the deployment",
                "Ordering rules: payment methods, fees, tax, tips, accept time",
                "Delivery rules: rider pay, dispatch timings, proof of delivery" })
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
