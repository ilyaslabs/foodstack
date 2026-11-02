package io.github.ilyaslabs.foodstack.notification.core.application;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The built-in message texts (PRD NO-2), per template and language: English, Arabic and Urdu ship
 * with FoodStack; other languages fall back to English. Placeholders look like {@code {{number}}}.
 * An admin editor for these texts comes in M4.
 */
public class Templates {

    public static final String FALLBACK = "en";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");

    private final Map<String, Map<String, Map<String, String>>> texts;

    public Templates(JsonMapper jsonMapper) {
        try (InputStream in = Templates.class.getResourceAsStream("/notification/templates.json")) {
            this.texts = jsonMapper.readValue(in, new TypeReference<>() {
            });
        }
        catch (IOException ex) {
            throw new IllegalStateException("Cannot read the notification templates", ex);
        }
    }

    /**
     * A message.
     *
     * @param title headline (in-app) and email heading
     * @param body text
     * @param subject email subject
     */
    public record Rendered(String title, String body, String subject) {
    }

    public Rendered render(String template, String language, Map<String, ?> params) {
        Map<String, String> text = text(template, language);
        return new Rendered(fill(text.get("title"), params), fill(text.get("body"), params), fill(text.get("subject"), params));
    }

    /** A short phrase used inside messages, such as the word for pickup. */
    public String phrase(String key, String language, Map<String, ?> params) {
        return fill(text("phrases", language).getOrDefault(key, key), params);
    }

    public boolean exists(String template) {
        return this.texts.containsKey(template);
    }

    /** Whether a language is written right to left. */
    public static boolean rightToLeft(String language) {
        return "ar".equals(language) || "ur".equals(language) || "fa".equals(language) || "he".equals(language);
    }

    private Map<String, String> text(String template, String language) {
        Map<String, Map<String, String>> byLanguage = this.texts.get(template);
        if (byLanguage == null) {
            throw new IllegalArgumentException("No template " + template);
        }
        Map<String, String> text = byLanguage.get(language);
        return (text != null) ? text : byLanguage.get(FALLBACK);
    }

    static String fill(String text, Map<String, ?> params) {
        if (text == null) {
            return "";
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            Object value = params.get(matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement((value != null) ? value.toString() : ""));
        }
        matcher.appendTail(out);
        return out.toString().strip();
    }

    static Map<String, Object> params(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
