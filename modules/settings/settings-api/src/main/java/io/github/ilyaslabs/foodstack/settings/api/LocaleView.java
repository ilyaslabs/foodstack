package io.github.ilyaslabs.foodstack.settings.api;

import java.util.List;

/**
 * Locale settings of the deployment.
 *
 * @param defaultLanguage BCP 47 tag used when a translation is missing
 * @param languages every enabled language, default first
 * @param currency ISO 4217 code of all prices
 * @param timezone IANA zone for opening hours and reports
 * @param version increases with every change
 */
public record LocaleView(String defaultLanguage, List<String> languages, String currency, String timezone, long version) {
}
