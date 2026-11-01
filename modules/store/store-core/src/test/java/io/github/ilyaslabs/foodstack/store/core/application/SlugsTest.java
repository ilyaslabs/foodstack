package io.github.ilyaslabs.foodstack.store.core.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SlugsTest {

    @Test
    @DisplayName("brand and branch names become a lower-case hyphenated address without accents")
    void of() {
        assertThat(Slugs.of("Spice Route", "Gulberg")).isEqualTo("spice-route-gulberg");
        assertThat(Slugs.of(" Café Déjà-Vu ", null)).isEqualTo("cafe-deja-vu");
        assertThat(Slugs.of("Fish & Chips", "DHA Phase 5")).isEqualTo("fish-and-chips-dha-phase-5");
        assertThat(Slugs.of("Tom's  Grill!!", "  ")).isEqualTo("tom-s-grill");
    }

    @Test
    @DisplayName("a name with no Latin letters falls back to store, and long names are cut at a word edge")
    void fallbackAndLength() {
        assertThat(Slugs.of("کراچی بریانی", null)).isEqualTo("store");
        String longName = "a".repeat(58) + " bb";
        assertThat(Slugs.of(longName, null)).hasSizeLessThanOrEqualTo(Slugs.MAX_LENGTH).doesNotEndWith("-");
        assertThat(Slugs.numbered("x".repeat(60), 12)).hasSize(60).endsWith("-12");
    }

    @Test
    @DisplayName("typed slugs must be lower-case words joined by single hyphens and not look like an id")
    void isValid() {
        assertThat(Slugs.isValid("spice-route")).isTrue();
        assertThat(Slugs.isValid("Spice-Route")).isFalse();
        assertThat(Slugs.isValid("spice--route")).isFalse();
        assertThat(Slugs.isValid("-spice")).isFalse();
        assertThat(Slugs.isValid("ab")).isFalse();
        assertThat(Slugs.isValid("0195f0a0-0000-7000-8000-000000000101")).isFalse();
    }
}
