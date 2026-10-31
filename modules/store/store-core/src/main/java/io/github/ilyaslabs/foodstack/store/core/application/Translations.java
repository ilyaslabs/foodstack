package io.github.ilyaslabs.foodstack.store.core.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks translated text (PRD ST-5): keys are the deployment's languages, the default language is
 * present when the text is required, and every value fits.
 */
final class Translations {

    private Translations() {
    }

    /** Trimmed copy without blank values, in the order of {@code languages}. */
    static Map<String, String> clean(Map<String, String> text, List<String> languages) {
        Map<String, String> clean = new LinkedHashMap<>();
        if (text == null) {
            return clean;
        }
        for (String language : languages) {
            String value = text.get(language);
            if (value != null && !value.isBlank()) {
                clean.put(language, value.trim());
            }
        }
        return clean;
    }

    static void validate(String field, Map<String, String> text, List<String> languages, String defaultLanguage,
            boolean required, int maxLength, Map<String, String> errors) {
        if (text != null) {
            for (Map.Entry<String, String> entry : text.entrySet()) {
                if (!languages.contains(entry.getKey())) {
                    errors.put(field, "has a language that is not enabled: " + entry.getKey());
                    return;
                }
                if (entry.getValue() != null && entry.getValue().trim().length() > maxLength) {
                    errors.put(field, "must be at most " + maxLength + " characters in every language");
                    return;
                }
            }
        }
        if (required && (text == null || text.get(defaultLanguage) == null || text.get(defaultLanguage).isBlank())) {
            errors.put(field, "is required in the default language (" + defaultLanguage + ")");
        }
    }
}
