package io.github.ilyaslabs.foodstack.settings.api;

import java.util.Map;

/**
 * Runtime branding of the deployment.
 *
 * @param name marketplace name shown everywhere
 * @param tagline short line under the name
 * @param primaryColor hex colour {@code #RRGGBB}; the apps derive Material 3 palettes from it
 * @param secondaryColor hex colour {@code #RRGGBB}
 * @param fontFamily CSS font family
 * @param logoMediaId media id of the logo, or {@code null}
 * @param logoUrls logo variant URLs by name ({@code thumb}, {@code card}, {@code detail}); empty without a logo
 * @param version increases with every change
 * @param updatedAt UTC epoch seconds, or {@code null} if never changed
 */
public record BrandingView(String name, String tagline, String primaryColor, String secondaryColor, String fontFamily,
        String logoMediaId, Map<String, String> logoUrls, long version, Long updatedAt) {
}
