package io.github.ilyaslabs.foodstack.settings.core.application;

import java.time.Instant;
import java.util.Map;

/**
 * Branding with the logo resolved to URLs.
 *
 * @param name marketplace name
 * @param tagline short line under the name
 * @param primaryColor {@code #RRGGBB}
 * @param secondaryColor {@code #RRGGBB}
 * @param fontFamily CSS font family
 * @param logoMediaId logo media id, or {@code null}
 * @param logoUrls logo variant URLs by name
 * @param version current version
 * @param updatedAt last change, or {@code null}
 */
public record BrandingDetails(String name, String tagline, String primaryColor, String secondaryColor, String fontFamily,
        String logoMediaId, Map<String, String> logoUrls, long version, Instant updatedAt) {
}
