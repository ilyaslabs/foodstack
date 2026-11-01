package io.github.ilyaslabs.foodstack.store.core.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TranslationsTest {

    @Test
    @DisplayName("accepts text saved before the operator turned a language off, and drops it on save")
    void languageTurnedOff() {
        // given: Arabic and Urdu were turned off after the description was written
        Map<String, String> description = Map.of("en", "Charcoal grills", "ar", "مشويات", "ur", "تکے");
        Map<String, String> errors = new LinkedHashMap<>();

        // when
        Translations.validate("description", description, List.of("en"), "en", false, 500, errors);

        // then
        assertThat(errors).isEmpty();
        assertThat(Translations.clean(description, List.of("en"))).containsExactly(Map.entry("en", "Charcoal grills"));
    }

    @Test
    @DisplayName("still requires the default language and limits the length")
    void stillChecked() {
        Map<String, String> errors = new LinkedHashMap<>();

        Translations.validate("name", Map.of("ar", "برياني"), List.of("en"), "en", true, 60, errors);
        Translations.validate("description", Map.of("en", "x".repeat(61)), List.of("en"), "en", false, 60, errors);

        assertThat(errors).containsOnlyKeys("name", "description");
    }
}
