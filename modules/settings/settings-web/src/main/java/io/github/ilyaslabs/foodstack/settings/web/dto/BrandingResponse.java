package io.github.ilyaslabs.foodstack.settings.web.dto;

import java.util.Map;

/**
 * {@code GET /api/v1/settings/branding}: everything the apps need to theme themselves at runtime.
 *
 * @param name marketplace name
 * @param tagline short line under the name
 * @param primaryColor {@code #RRGGBB}
 * @param secondaryColor {@code #RRGGBB}
 * @param fontFamily CSS font family
 * @param logo logo media id and URLs, or {@code null}
 * @param version current version (also the ETag)
 * @param updatedAt UTC epoch seconds, or {@code null}
 */
public record BrandingResponse(String name, String tagline, String primaryColor, String secondaryColor, String fontFamily,
        Logo logo, long version, Long updatedAt) {

    public record Logo(String mediaId, Map<String, String> urls) {
    }
}
