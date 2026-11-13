package io.github.ilyaslabs.foodstack.settings.web.dto;

import java.util.List;

import io.github.ilyaslabs.evl.EVL;

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
@EVL.Response(id = "settings.locale-response", name = "Locale Response")
public record LocaleResponse(String defaultLanguage, List<String> languages, List<String> rtlLanguages, String currency,
        String timezone, long version) {
}
