package io.github.ilyaslabs.foodstack.settings.web.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/**
 * {@code PUT /api/v1/settings/locale} with {@code If-Match}.
 *
 * @param defaultLanguage one of {@code languages}
 * @param languages BCP 47 tags
 * @param currency ISO 4217 code
 * @param timezone IANA zone id
 */
public record LocaleRequest(@NotBlank String defaultLanguage, @NotEmpty List<String> languages, @NotBlank String currency,
        @NotBlank String timezone) {
}
