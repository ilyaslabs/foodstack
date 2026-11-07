package io.github.ilyaslabs.foodstack.notification.core.application;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.notification.core.NotificationConfiguration;
import io.github.ilyaslabs.foodstack.notification.core.application.TemplateEditor.Text;
import io.github.ilyaslabs.foodstack.notification.core.persistence.TemplateOverrideDocument;
import io.github.ilyaslabs.foodstack.platform.mongo.FoodstackMongoProperties;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMigrations;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongo;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongoFactory;
import io.github.ilyaslabs.foodstack.platform.security.FoodstackJwtAuthenticationConverter;
import io.github.ilyaslabs.foodstack.platform.test.FoodstackContainers;
import io.github.ilyaslabs.foodstack.settings.api.BrandingView;
import io.github.ilyaslabs.foodstack.settings.api.LocaleView;
import io.github.ilyaslabs.foodstack.settings.api.SettingsApi;
import io.github.ilyaslabs.moduleswitch.ModuleException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/** The admin's message editor and rendering with edits, against a real two-shard MongoDB (TDD 15.3). */
@SpringBootTest(classes = TemplateEditorTest.App.class, properties = "spring.data.mongodb.repositories.type=none")
class TemplateEditorTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration(excludeName = {
            "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
            "org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration" })
    static class App {

        @Bean
        ModuleMongo notificationMongo(ModuleMongoFactory factory) {
            return factory.create("notification");
        }

        @Bean
        ModuleMigrations notificationMigrations(ModuleMongo notificationMongo, ModuleMongoFactory factory,
                FoodstackMongoProperties properties) {
            return new ModuleMigrations(notificationMongo, factory.connectionString("notification"),
                    NotificationConfiguration.shardKeys(), properties);
        }
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        FoodstackContainers.registerMongo(registry);
    }

    @Autowired
    ModuleMongo notificationMongo;

    private final SettingsApi settings = mock(SettingsApi.class);

    private Templates templates;

    private TemplateEditor editor;

    @BeforeEach
    void setUp() {
        this.notificationMongo.template().remove(new Query(), TemplateOverrideDocument.class);
        given(this.settings.getLocale()).willReturn(new LocaleView("en", List.of("en", "ur", "fr"), "PKR", "Asia/Karachi", 1));
        given(this.settings.getBranding()).willReturn(new BrandingView("Lahore Eats", null, "#B3261E", null, null, null,
                Map.of(), 1, null));
        this.templates = new Templates(JsonMapper.builder().build(), this.notificationMongo.template());
        this.editor = new TemplateEditor(this.templates, this.notificationMongo.template(), this.settings, Clock.systemUTC());
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "ES256").subject("admin-1").claim("perms", List.of()).build();
        SecurityContextHolder.getContext().setAuthentication(new FoodstackJwtAuthenticationConverter().convert(jwt));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("an edit wins field by field over the built-in text, and going back restores it")
    void editAndReset() {
        Map<String, ?> params = Map.of("number", "A-7", "store", "Napoli", "codeLine", "");
        String builtInTitle = this.templates.render("order-confirmed", "en", params).title();

        TemplateEditor.LanguageText saved = this.editor.save("order-confirmed", "en",
                new Text(null, "Thanks! {{store}} is looking at order {{number}}.", "  "), 0L);
        assertThat(saved.version()).isEqualTo(1);
        assertThat(saved.edited().body()).isEqualTo("Thanks! {{store}} is looking at order {{number}}.");
        assertThat(saved.edited().subject()).isNull();

        Templates.Rendered rendered = this.templates.render("order-confirmed", "en", params);
        assertThat(rendered.body()).isEqualTo("Thanks! Napoli is looking at order A-7.");
        assertThat(rendered.title()).isEqualTo(builtInTitle);
        assertThat(rendered.subject()).isEqualTo("Your order A-7 from Napoli");
        assertThat(this.editor.list()).filteredOn((s) -> s.id().equals("order-confirmed"))
            .singleElement()
            .satisfies((s) -> assertThat(s.editedLanguages()).containsExactly("en"));
        // French has no built-in text, so it shows English, and the operator's English too
        assertThat(this.templates.render("order-confirmed", "fr", params).body()).startsWith("Thanks! Napoli");

        assertThatThrownBy(() -> this.editor.save("order-confirmed", "en", new Text("Hi", null, null), 0L))
            .isInstanceOf(ModuleException.Conflict.class);
        assertThat(this.editor.save("order-confirmed", "en", new Text("Hi {{store}}", null, null), 1L).version()).isEqualTo(2);

        TemplateEditor.LanguageText reset = this.editor.reset("order-confirmed", "en");
        assertThat(reset.edited()).isNull();
        assertThat(reset.version()).isZero();
        assertThat(this.templates.render("order-confirmed", "en", params).title()).isEqualTo(builtInTitle);
    }

    @Test
    @DisplayName("refuses unknown placeholders, a code message without its code, empty edits and languages not turned on")
    void validation() {
        assertThatThrownBy(() -> this.editor.save("order-confirmed", "en", new Text("Hi {{rider}}", null, null), 0L))
            .isInstanceOfSatisfying(ModuleException.BadRequest.class,
                    (ex) -> assertThat(ex.code()).isEqualTo("TEMPLATE_INVALID"));
        assertThatThrownBy(() -> this.editor.save("login-code", "en", new Text(null, "Sign in now.", null), 0L))
            .isInstanceOf(ModuleException.BadRequest.class);
        assertThat(this.editor.save("login-code", "en", new Text(null, "Your code: {{code}}", null), 0L).version()).isEqualTo(1);
        assertThatThrownBy(() -> this.editor.save("order-confirmed", "en", new Text(" ", "", null), 0L))
            .isInstanceOf(ModuleException.BadRequest.class);
        assertThatThrownBy(() -> this.editor.save("order-confirmed", "ar", new Text("مرحبا", null, null), 0L))
            .isInstanceOf(ModuleException.BadRequest.class);
        assertThatThrownBy(() -> this.editor.get("phrases")).isInstanceOf(ModuleException.NotFound.class);
        assertThatThrownBy(() -> this.editor.save("order-confirmed", "en", new Text("x".repeat(201), null, null), 0L))
            .isInstanceOf(ModuleException.BadRequest.class);
    }

    @Test
    @DisplayName("lists every enabled language with its built-in text, and previews the email with sample values")
    void detailsAndPreview() {
        TemplateEditor.Details details = this.editor.get("order-accepted");
        assertThat(details.placeholders()).containsExactly("store", "number", "minutes");
        assertThat(details.languages()).extracting(TemplateEditor.LanguageText::language).containsExactly("en", "ur", "fr");
        assertThat(details.languages().get(2).builtInLanguage()).isEqualTo("en");
        assertThat(details.languages().get(1).builtIn().title()).contains("{{store}}");
        assertThat(this.editor.get("login-code").required()).containsExactly("code");

        TemplateEditor.Preview preview = this.editor.preview("order-accepted", "ur",
                new Text(null, "{{store}} میں آرڈر {{number}} تیار ہو رہا ہے۔", null));
        assertThat(preview.body()).isEqualTo("Spice Route Gulberg میں آرڈر A-1042 تیار ہو رہا ہے۔");
        assertThat(preview.subject()).contains("A-1042");
        assertThat(preview.html()).contains("dir=\"rtl\"").contains("Lahore Eats").contains("#B3261E");
    }
}
