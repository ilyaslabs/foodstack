package io.github.ilyaslabs.foodstack.notification.core.application;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.ilyaslabs.foodstack.notification.core.persistence.TemplateOverrideDocument;

import org.springframework.data.mongodb.core.MongoTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The built-in message texts (PRD NO-2), per template and language: English, Arabic and Urdu ship
 * with FoodStack; other languages fall back to English. Placeholders look like {@code {{number}}}.
 * The operator's own wording, saved in the template editor, wins over the built-in text field by
 * field (M4).
 */
public class Templates {

    public static final String FALLBACK = "en";

    static final String PHRASES = "phrases";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");

    private final Map<String, Map<String, Map<String, String>>> texts;

    private final MongoTemplate mongo;

    /** Built-in texts only. */
    public Templates(JsonMapper jsonMapper) {
        this(jsonMapper, null);
    }

    /** Built-in texts, with the operator's edits read from {@code notification_templates}. */
    public Templates(JsonMapper jsonMapper, MongoTemplate mongo) {
        this.mongo = mongo;
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
        TemplateOverrideDocument edited = override(template, language);
        return new Rendered(fill(pick(edited, "title", text), params), fill(pick(edited, "body", text), params),
                fill(pick(edited, "subject", text), params));
    }

    /** The template ids people can edit (every message, not the shared phrases). */
    public List<String> ids() {
        return this.texts.keySet().stream().filter((id) -> !PHRASES.equals(id)).toList();
    }

    /** The built-in text of a template in a language, or {@code null} when it has none there. */
    public Map<String, String> builtIn(String template, String language) {
        Map<String, Map<String, String>> byLanguage = this.texts.get(template);
        return (byLanguage != null) ? byLanguage.get(language) : null;
    }

    /** The placeholders a template's built-in English text uses, in order of first use. */
    public List<String> placeholders(String template) {
        Map<String, String> text = builtIn(template, FALLBACK);
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        if (text != null) {
            for (String field : List.of("title", "subject", "body")) {
                Matcher matcher = PLACEHOLDER.matcher(text.getOrDefault(field, ""));
                while (matcher.find()) {
                    names.add(matcher.group(1));
                }
            }
        }
        return List.copyOf(names);
    }

    /** The placeholder names used in a text. */
    static java.util.Set<String> placeholdersIn(String text) {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher((text != null) ? text : "");
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private TemplateOverrideDocument override(String template, String language) {
        if (this.mongo == null) {
            return null;
        }
        TemplateOverrideDocument edited = this.mongo.findById(TemplateOverrideDocument.id(template, language),
                TemplateOverrideDocument.class);
        if (edited == null && !FALLBACK.equals(language) && builtIn(template, language) == null) {
            // a language without built-in text shows English, so it also shows the operator's English
            edited = this.mongo.findById(TemplateOverrideDocument.id(template, FALLBACK), TemplateOverrideDocument.class);
        }
        return edited;
    }

    private static String pick(TemplateOverrideDocument edited, String field, Map<String, String> builtIn) {
        if (edited != null) {
            String value = switch (field) {
                case "title" -> edited.title();
                case "body" -> edited.body();
                default -> edited.subject();
            };
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return builtIn.get(field);
    }

    /** A short phrase used inside messages, such as the word for pickup. */
    public String phrase(String key, String language, Map<String, ?> params) {
        return fill(text(PHRASES, language).getOrDefault(key, key), params);
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
