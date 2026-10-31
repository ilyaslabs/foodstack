package io.github.ilyaslabs.foodstack.settings.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.platform.security.ApiSecurityConfiguration;
import io.github.ilyaslabs.foodstack.platform.web.ProblemDetailsAdvice;
import io.github.ilyaslabs.foodstack.settings.core.application.BrandingDetails;
import io.github.ilyaslabs.foodstack.settings.core.application.LocaleDetails;
import io.github.ilyaslabs.foodstack.settings.core.application.SettingsService;
import io.github.ilyaslabs.foodstack.settings.core.application.UpdateBranding;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SettingsController.class, properties = "module-switch.modules.settings.mode=local")
@Import({ ApiSecurityConfiguration.class, ProblemDetailsAdvice.class, SettingsWebConfiguration.class })
class SettingsControllerTest {

    private static final String BODY = """
            {"name":"Karachi Eats","tagline":"Fast","primaryColor":"#C62828","secondaryColor":"#2E7D32",
             "fontFamily":"Inter","logoMediaId":"logo-1"}""";

    @SpringBootApplication
    static class App {
    }

    @MockitoBean
    SettingsService settingsService;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    MockMvc mvc;

    @Test
    @DisplayName("GET branding is public and carries an ETag of its version")
    void getBranding() throws Exception {
        given(this.settingsService.getBranding()).willReturn(branding(4));

        this.mvc.perform(get("/api/v1/settings/branding"))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", "\"branding-4\""))
            .andExpect(jsonPath("$.name").value("FoodStack"))
            .andExpect(jsonPath("$.logo.urls.thumb").value("https://cdn/logo/thumb.webp"))
            .andExpect(jsonPath("$.updatedAt").value(1_800_000_000L));
    }

    @Test
    @DisplayName("answers 304 when the client already has the current version")
    void notModified() throws Exception {
        given(this.settingsService.getBranding()).willReturn(branding(4));

        this.mvc.perform(get("/api/v1/settings/branding").header("If-None-Match", "\"branding-4\""))
            .andExpect(status().isNotModified());
    }

    @Test
    @DisplayName("PUT branding needs settings:manage and passes the If-Match version to the service")
    void updateBranding() throws Exception {
        given(this.settingsService.updateBranding(
                eq(new UpdateBranding("Karachi Eats", "Fast", "#C62828", "#2E7D32", "Inter", "logo-1")), eq(4L), eq("admin-1")))
            .willReturn(branding(5));

        this.mvc.perform(put("/api/v1/settings/branding").with(admin())
            .header("If-Match", "\"branding-4\"")
            .contentType(MediaType.APPLICATION_JSON)
            .content(BODY))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", "\"branding-5\""));
    }

    @Test
    @DisplayName("refuses edits without a token, without the permission or without If-Match")
    void updateRules() throws Exception {
        this.mvc.perform(put("/api/v1/settings/branding").contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isUnauthorized());
        this.mvc.perform(put("/api/v1/settings/branding").with(jwt()).header("If-Match", "\"branding-4\"")
            .contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isForbidden());
        this.mvc.perform(put("/api/v1/settings/branding").with(admin()).contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("IF_MATCH_REQUIRED"));
    }

    @Test
    @DisplayName("reports a lost-update conflict as 409 with its code")
    void conflict() throws Exception {
        given(this.settingsService.updateBranding(any(), anyLong(), any()))
            .willThrow(new ModuleException.Conflict("changed", "SETTINGS_VERSION_CONFLICT"));

        this.mvc.perform(put("/api/v1/settings/branding").with(admin()).header("If-Match", "\"branding-3\"")
            .contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("SETTINGS_VERSION_CONFLICT"));
    }

    @Test
    @DisplayName("GET locale is public and lists the right-to-left languages for the apps")
    void locale() throws Exception {
        given(this.settingsService.getLocale())
            .willReturn(new LocaleDetails("en", List.of("en", "ar", "ur-PK"), "PKR", "Asia/Karachi", 2, null));

        this.mvc.perform(get("/api/v1/settings/locale"))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", "\"locale-2\""))
            .andExpect(jsonPath("$.rtlLanguages[0]").value("ar"))
            .andExpect(jsonPath("$.rtlLanguages[1]").value("ur-PK"));
    }

    private static JwtRequestPostProcessor admin() {
        return jwt().jwt((token) -> token.subject("admin-1"))
            .authorities(List.of(new SimpleGrantedAuthority("settings:manage")));
    }

    private static BrandingDetails branding(long version) {
        return new BrandingDetails("FoodStack", "Fast", "#C62828", "#2E7D32", "Inter", "logo-1",
                Map.of("thumb", "https://cdn/logo/thumb.webp"), version, Instant.ofEpochSecond(1_800_000_000L));
    }
}
