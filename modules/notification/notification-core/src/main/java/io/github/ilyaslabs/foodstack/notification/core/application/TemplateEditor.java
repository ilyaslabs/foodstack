package io.github.ilyaslabs.foodstack.notification.core.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.ilyaslabs.foodstack.notification.core.persistence.TemplateOverrideDocument;
import io.github.ilyaslabs.foodstack.platform.security.CurrentUser;
import io.github.ilyaslabs.foodstack.settings.api.BrandingView;
import io.github.ilyaslabs.foodstack.settings.api.LocaleView;
import io.github.ilyaslabs.foodstack.settings.api.SettingsApi;
import io.github.ilyaslabs.moduleswitch.ModuleException;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * The admin's message editor (PRD NO-2): every message in every enabled language, with the
 * built-in text beside the operator's own wording, a preview of the email, and a way back to the
 * built-in text. An edit may use only the placeholders the message already has, and a sign-in or
 * reset code message must keep its code.
 */
public class TemplateEditor {

    static final int TITLE_MAX = 200;

    static final int BODY_MAX = 2000;

    /** Values shown in a preview in place of each placeholder. */
    private static final Map<String, String> SAMPLES = Map.ofEntries(Map.entry("amount", "PKR 1,250"),
            Map.entry("brand", "Spice Route"), Map.entry("code", "482913"),
            Map.entry("codeLine", "Your delivery code is 4829. Give it to the rider when your order arrives."),
            Map.entry("date", "21 November 2026"), Map.entry("earning", "PKR 350"), Map.entry("fulfilment", "delivery"),
            Map.entry("items", "2 × Chicken karahi, 1 × Garlic naan"), Map.entry("limit", "PKR 50,000"),
            Map.entry("minutes", "25"), Map.entry("name", "Ayesha"), Map.entry("number", "A-1042"),
            Map.entry("owed", "PKR 61,200"), Map.entry("reason", "The restaurant is closing early today."),
            Map.entry("refund", "PKR 1,250 will be refunded to your card."), Map.entry("rider", "Bilal"),
            Map.entry("store", "Spice Route Gulberg"), Map.entry("total", "PKR 3,600"));

    private final Templates templates;

    private final MongoTemplate mongo;

    private final SettingsApi settings;

    private final Clock clock;

    public TemplateEditor(Templates templates, MongoTemplate mongo, SettingsApi settings, Clock clock) {
        this.templates = templates;
        this.mongo = mongo;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * One message in the list.
     *
     * @param id template id
     * @param placeholders what it can show
     * @param editedLanguages languages the operator has reworded
     */
    public record Summary(String id, List<String> placeholders, List<String> editedLanguages) {
    }

    /**
     * A message's text in one language.
     *
     * @param language BCP 47 tag
     * @param builtIn the text FoodStack ships (English for a language without its own)
     * @param builtInLanguage the language of {@code builtIn}
     * @param edited the operator's wording, or {@code null}
     * @param version the edit's version for If-Match ({@code 0} when not edited)
     * @param updatedAt when it was last saved
     */
    public record LanguageText(String language, Text builtIn, String builtInLanguage, Text edited, long version,
            Instant updatedAt) {
    }

    /** A title, body and subject; a blank field keeps the built-in one. */
    public record Text(String title, String body, String subject) {
    }

    /**
     * A message with its text in every enabled language.
     *
     * @param id template id
     * @param placeholders what it can show
     * @param required placeholders every edit must keep
     * @param languages texts, the default language first
     */
    public record Details(String id, List<String> placeholders, List<String> required, List<LanguageText> languages) {
    }

    /** A preview: the email's subject, its HTML, and the in-app title and text. */
    public record Preview(String subject, String title, String body, String html) {
    }

    public List<Summary> list() {
        Map<String, List<String>> edited = new LinkedHashMap<>();
        for (TemplateOverrideDocument doc : this.mongo.findAll(TemplateOverrideDocument.class)) {
            edited.computeIfAbsent(doc.template(), (k) -> new ArrayList<>()).add(doc.language());
        }
        return this.templates.ids()
            .stream()
            .map((id) -> new Summary(id, this.templates.placeholders(id), edited.getOrDefault(id, List.of())))
            .toList();
    }

    public Details get(String template) {
        require(template);
        List<LanguageText> texts = new ArrayList<>();
        for (String language : languages()) {
            texts.add(text(template, language));
        }
        return new Details(template, this.templates.placeholders(template), required(template), texts);
    }

    /**
     * Saves the operator's wording of a message in a language.
     *
     * @param expectedVersion the version read ({@code 0} for a message not edited yet), or
     *     {@code null} to skip the check
     */
    public LanguageText save(String template, String language, Text text, Long expectedVersion) {
        require(template);
        requireLanguage(language);
        validate(template, text);
        String id = TemplateOverrideDocument.id(template, language);
        TemplateOverrideDocument current = this.mongo.findById(id, TemplateOverrideDocument.class);
        long version = (current != null) ? current.version() : 0;
        if (expectedVersion != null && expectedVersion != version) {
            throw new ModuleException.Conflict("Someone else changed this message; reload to see their text",
                    "TEMPLATE_CHANGED", Map.of("version", version));
        }
        TemplateOverrideDocument next = new TemplateOverrideDocument(id, template, language, clean(text.title()),
                clean(text.body()), clean(text.subject()), version + 1, this.clock.instant(), CurrentUser.id());
        if (current == null) {
            try {
                this.mongo.insert(next);
            }
            catch (DuplicateKeyException ex) {
                throw new ModuleException.Conflict("Someone else changed this message; reload to see their text",
                        "TEMPLATE_CHANGED");
            }
        }
        else {
            TemplateOverrideDocument replaced = this.mongo.findAndReplace(
                    Query.query(Criteria.where("_id").is(id).and("version").is(version)), next);
            if (replaced == null) {
                throw new ModuleException.Conflict("Someone else changed this message; reload to see their text",
                        "TEMPLATE_CHANGED");
            }
        }
        return text(template, language);
    }

    /** Goes back to the built-in text of a message in a language. */
    public LanguageText reset(String template, String language) {
        require(template);
        requireLanguage(language);
        this.mongo.remove(Query.query(Criteria.where("_id").is(TemplateOverrideDocument.id(template, language))),
                TemplateOverrideDocument.class);
        return text(template, language);
    }

    /** The email and in-app message as a customer would get them, with sample values. */
    public Preview preview(String template, String language, Text text) {
        require(template);
        Map<String, String> builtIn = builtInOrEnglish(template, language);
        Map<String, String> params = new LinkedHashMap<>();
        for (String name : this.templates.placeholders(template)) {
            params.put(name, SAMPLES.getOrDefault(name, "…"));
        }
        String title = Templates.fill(blankTo(text.title(), builtIn.get("title")), params);
        String body = Templates.fill(blankTo(text.body(), builtIn.get("body")), params);
        String subject = Templates.fill(blankTo(text.subject(), builtIn.get("subject")), params);
        BrandingView branding = this.settings.getBranding();
        String brand = (branding != null && branding.name() != null) ? branding.name() : "FoodStack";
        String button = this.templates.phrase("button", language, Map.of());
        String html = EmailLayout.html(brand, (branding != null) ? branding.primaryColor() : null, language, title, body,
                button, "https://example.com/orders/A-1042");
        return new Preview(subject, title, body, html);
    }

    private LanguageText text(String template, String language) {
        Map<String, String> own = this.templates.builtIn(template, language);
        Map<String, String> builtIn = (own != null) ? own : this.templates.builtIn(template, Templates.FALLBACK);
        TemplateOverrideDocument doc = this.mongo.findById(TemplateOverrideDocument.id(template, language),
                TemplateOverrideDocument.class);
        return new LanguageText(language, toText(builtIn), (own != null) ? language : Templates.FALLBACK,
                (doc != null) ? new Text(doc.title(), doc.body(), doc.subject()) : null, (doc != null) ? doc.version() : 0,
                (doc != null) ? doc.updatedAt() : null);
    }

    private void validate(String template, Text text) {
        Map<String, String> errors = new LinkedHashMap<>();
        Set<String> allowed = new LinkedHashSet<>(this.templates.placeholders(template));
        check(errors, "title", text.title(), TITLE_MAX, allowed);
        check(errors, "subject", text.subject(), TITLE_MAX, allowed);
        check(errors, "body", text.body(), BODY_MAX, allowed);
        if (text.body() != null && !text.body().isBlank()) {
            for (String name : required(template)) {
                if (!Templates.placeholdersIn(text.body()).contains(name)) {
                    errors.putIfAbsent("body", "must keep {{" + name + "}}");
                }
            }
        }
        if (blank(text.title()) && blank(text.body()) && blank(text.subject())) {
            errors.put("body", "write at least one field, or go back to the built-in text");
        }
        if (!errors.isEmpty()) {
            throw new ModuleException.BadRequest("Check the message text", "TEMPLATE_INVALID", Map.of("errors", errors));
        }
    }

    private static void check(Map<String, String> errors, String field, String value, int max, Set<String> allowed) {
        if (value == null) {
            return;
        }
        if (value.length() > max) {
            errors.put(field, "must be at most " + max + " characters");
            return;
        }
        for (String name : Templates.placeholdersIn(value)) {
            if (!allowed.contains(name)) {
                errors.put(field, "{{" + name + "}} is not available in this message");
                return;
            }
        }
    }

    /** Placeholders a message must keep: the code in sign-in and reset messages. */
    private List<String> required(String template) {
        Map<String, String> builtIn = this.templates.builtIn(template, Templates.FALLBACK);
        return Templates.placeholdersIn(builtIn.get("body")).contains("code") && template.endsWith("-code") ? List.of("code")
                : List.of();
    }

    private Map<String, String> builtInOrEnglish(String template, String language) {
        Map<String, String> own = this.templates.builtIn(template, language);
        return (own != null) ? own : this.templates.builtIn(template, Templates.FALLBACK);
    }

    private List<String> languages() {
        LocaleView locale = this.settings.getLocale();
        List<String> languages = (locale != null && locale.languages() != null && !locale.languages().isEmpty())
                ? locale.languages() : List.of(Templates.FALLBACK);
        return languages;
    }

    private void require(String template) {
        if (template == null || !this.templates.ids().contains(template)) {
            throw new ModuleException.NotFound("No such message", "TEMPLATE_NOT_FOUND");
        }
    }

    private void requireLanguage(String language) {
        if (!languages().contains(language)) {
            throw new ModuleException.BadRequest("That language is not turned on", "LANGUAGE_NOT_ENABLED");
        }
    }

    private static Text toText(Map<String, String> text) {
        return new Text(text.get("title"), text.get("body"), text.get("subject"));
    }

    private static String clean(String value) {
        return blank(value) ? null : value.strip();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankTo(String value, String fallback) {
        return blank(value) ? fallback : value;
    }
}
