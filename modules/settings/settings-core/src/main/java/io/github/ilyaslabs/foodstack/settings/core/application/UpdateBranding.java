package io.github.ilyaslabs.foodstack.settings.core.application;

/**
 * New branding values; every field is replaced.
 *
 * @param name 1 to 60 characters
 * @param tagline up to 120 characters, may be {@code null}
 * @param primaryColor {@code #RRGGBB}
 * @param secondaryColor {@code #RRGGBB}
 * @param fontFamily letters, digits, spaces and hyphens
 * @param logoMediaId a ready BRANDING image, or {@code null} for no logo
 */
public record UpdateBranding(String name, String tagline, String primaryColor, String secondaryColor, String fontFamily,
        String logoMediaId) {
}
