package io.github.ilyaslabs.foodstack.settings.web.dto;

import java.util.List;

/**
 * {@code GET /api/v1/settings/locale}.
 *
 * @param defaultLanguage BCP 47 tag
 * @param languages enabled languages, default first
 * @param rtlLanguages the enabled languages written right to left
 * @param currency ISO 4217 code
 * @param timezone IANA zone id
 * @param version current version (also the ETag)
 */
public record LocaleResponse(String defaultLanguage, List<String> languages, List<String> rtlLanguages, String currency,
        String timezone, long version) {
}
