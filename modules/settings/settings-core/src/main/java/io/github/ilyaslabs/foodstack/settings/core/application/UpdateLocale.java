package io.github.ilyaslabs.foodstack.settings.core.application;

import java.util.List;

/**
 * New locale values.
 *
 * @param defaultLanguage must be one of {@code languages}
 * @param languages BCP 47 tags
 * @param currency ISO 4217 code
 * @param timezone IANA zone id
 */
public record UpdateLocale(String defaultLanguage, List<String> languages, String currency, String timezone) {
}
