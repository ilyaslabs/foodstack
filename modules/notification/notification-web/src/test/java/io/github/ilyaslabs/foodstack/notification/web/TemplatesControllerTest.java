package io.github.ilyaslabs.foodstack.notification.web;

import java.util.List;

import io.github.ilyaslabs.foodstack.notification.core.application.TemplateEditor;
import io.github.ilyaslabs.foodstack.notification.core.application.TemplateEditor.Text;
import io.github.ilyaslabs.foodstack.platform.security.ApiSecurityConfiguration;
import io.github.ilyaslabs.foodstack.platform.web.ProblemDetailsAdvice;
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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = TemplatesController.class, properties = "module-switch.modules.notification.mode=local")
@Import({ ApiSecurityConfiguration.class, ProblemDetailsAdvice.class })
class TemplatesControllerTest {

    @SpringBootApplication
    static class App {
    }

    @MockitoBean
    TemplateEditor editor;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    MockMvc mvc;

    private static JwtRequestPostProcessor admin() {
        return jwt().jwt((t) -> t.subject("admin-1")).authorities(new SimpleGrantedAuthority("settings:manage"));
    }

    @Test
    @DisplayName("only the operator's settings managers see and change messages")
    void access() throws Exception {
        given(this.editor.list()).willReturn(List.of(new TemplateEditor.Summary("order-confirmed",
                List.of("number", "store"), List.of("en"))));

        this.mvc.perform(get("/api/v1/notifications/templates")).andExpect(status().isUnauthorized());
        this.mvc.perform(get("/api/v1/notifications/templates").with(jwt())).andExpect(status().isForbidden());
        this.mvc.perform(get("/api/v1/notifications/templates").with(admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value("order-confirmed"))
            .andExpect(jsonPath("$[0].editedLanguages[0]").value("en"));
    }

    @Test
    @DisplayName("saves with the version read, goes back to built-in text, and previews without saving")
    void saveResetPreview() throws Exception {
        TemplateEditor.Text builtIn = new Text("Order {{number}} received", "Body", "Subject");
        given(this.editor.save("order-confirmed", "en", new Text("Hi {{number}}", null, null), 0L))
            .willReturn(new TemplateEditor.LanguageText("en", builtIn, "en", new Text("Hi {{number}}", null, null), 1, null));
        willThrow(new ModuleException.Conflict("changed", "TEMPLATE_CHANGED")).given(this.editor)
            .save("order-confirmed", "en", new Text("Hi", null, null), 3L);
        given(this.editor.reset("order-confirmed", "en"))
            .willReturn(new TemplateEditor.LanguageText("en", builtIn, "en", null, 0, null));
        given(this.editor.preview(eq("order-confirmed"), eq("ur"), eq(new Text(null, "x", null))))
            .willReturn(new TemplateEditor.Preview("S", "T", "x", "<html></html>"));

        this.mvc.perform(put("/api/v1/notifications/templates/order-confirmed/en").with(admin())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Hi {{number}}\",\"version\":0}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.edited.title").value("Hi {{number}}"));
        this.mvc.perform(put("/api/v1/notifications/templates/order-confirmed/en").with(admin())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Hi\",\"version\":3}"))
            .andExpect(status().isConflict());
        this.mvc.perform(delete("/api/v1/notifications/templates/order-confirmed/en").with(admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.edited").doesNotExist());
        this.mvc.perform(post("/api/v1/notifications/templates/order-confirmed/ur/preview").with(admin())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"body\":\"x\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.html").value("<html></html>"));
    }
}
