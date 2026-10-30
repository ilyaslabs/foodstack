package io.github.ilyaslabs.foodstack.settings.api;

/** What other modules may ask the settings module. Both answers are cached and cheap to call. */
public interface SettingsApi {

    /** The operator's branding (name, colours, logo). */
    BrandingView getBranding();

    /** Languages, currency and timezone of the deployment (one of each per deployment, BRD). */
    LocaleView getLocale();
}
