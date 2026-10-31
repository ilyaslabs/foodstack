package io.github.ilyaslabs.foodstack.settings.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code PUT /api/v1/settings/branding} with {@code If-Match}.
 *
 * @param name marketplace name
 * @param tagline optional
 * @param primaryColor {@code #RRGGBB}
 * @param secondaryColor {@code #RRGGBB}
 * @param fontFamily CSS font family
 * @param logoMediaId a processed BRANDING upload, or {@code null}
 */
public record BrandingRequest(@NotBlank @Size(max = 60) String name, @Size(max = 120) String tagline,
        @NotBlank @Pattern(regexp = "#[0-9A-Fa-f]{6}") String primaryColor,
        @NotBlank @Pattern(regexp = "#[0-9A-Fa-f]{6}") String secondaryColor, @NotBlank String fontFamily,
        String logoMediaId) {
}
