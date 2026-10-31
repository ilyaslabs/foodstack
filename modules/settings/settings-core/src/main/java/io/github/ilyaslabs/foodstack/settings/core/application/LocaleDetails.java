package io.github.ilyaslabs.foodstack.settings.core.application;

import java.time.Instant;
import java.util.List;

/**
 * Locale settings.
 *
 * @param defaultLanguage BCP 47 tag
 * @param languages enabled languages, default first
 * @param currency ISO 4217 code
 * @param timezone IANA zone id
 * @param version current version
 * @param updatedAt last change, or {@code null}
 */
public record LocaleDetails(String defaultLanguage, List<String> languages, String currency, String timezone,
        long version, Instant updatedAt) {
}
