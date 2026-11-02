package io.github.ilyaslabs.foodstack.notification.core.application;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class TemplatesTest {

    private final Templates templates = new Templates(JsonMapper.builder().build());

    @Test
    @DisplayName("every template has English, Arabic and Urdu with the same placeholders")
    @SuppressWarnings("unchecked")
    void complete() throws Exception {
        Map<String, Map<String, Map<String, String>>> all = JsonMapper.builder().build()
            .readValue(Templates.class.getResourceAsStream("/notification/templates.json"), Map.class);
        all.forEach((name, languages) -> {
            assertThat(languages).as(name).containsKeys("en", "ar", "ur");
            languages.get("en").forEach((field, english) -> {
                java.util.Set<String> expected = placeholders(english);
                for (String language : java.util.List.of("ar", "ur")) {
                    assertThat(languages.get(language)).as(name + "." + language).containsKey(field);
                    assertThat(placeholders(languages.get(language).get(field))).as(name + "." + language + "." + field)
                        .isEqualTo(expected);
                }
            });
        });
    }

    @Test
    @DisplayName("fills placeholders, falls back to English and knows right-to-left languages")
    void render() {
        Templates.Rendered rendered = this.templates.render("order-ready-pickup", "fr", Map.of("number", "7F3A-91C2", "store", "Karahi"));
        assertThat(rendered.title()).isEqualTo("Order 7F3A-91C2 is ready to collect");
        assertThat(this.templates.render("order-rejected", "en", Map.of("number", "1", "store", "K", "reason", "Busy.", "refund", ""))
            .body()).isEqualTo("Busy.");
        assertThat(Templates.rightToLeft("ar")).isTrue();
        assertThat(Templates.rightToLeft("en")).isFalse();
        assertThat(Money.format(255_000, "PKR")).isEqualTo("PKR 2,550.00");
        assertThat(Money.format(1_500, "JPY")).isEqualTo("JPY 1,500");
    }

    private static java.util.Set<String> placeholders(String text) {
        java.util.Set<String> found = new java.util.TreeSet<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\{\\{(\\w+)}}").matcher(text);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }
}
