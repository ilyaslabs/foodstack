package io.github.ilyaslabs.foodstack.identity.web;

import java.time.Instant;
import java.util.List;

import io.github.ilyaslabs.foodstack.identity.core.application.AuditLine;
import io.github.ilyaslabs.foodstack.identity.core.application.AuditLog;
import io.github.ilyaslabs.foodstack.identity.core.application.AuditPage;
import io.github.ilyaslabs.foodstack.platform.security.ApiSecurityConfiguration;
import io.github.ilyaslabs.foodstack.platform.web.ProblemDetailsAdvice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuditController.class, properties = "module-switch.modules.identity.mode=local")
@Import({ ApiSecurityConfiguration.class, ProblemDetailsAdvice.class })
class AuditControllerTest {

    @SpringBootApplication
    static class App {
    }

    @MockitoBean
    AuditLog log;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    MockMvc mvc;

    @Test
    @DisplayName("lists entries for staff with identity:audit:read, with the cursor of the next page when the page is full")
    void list() throws Exception {
        AuditLine line = new AuditLine("e2", "WRITE", Instant.parse("2026-11-06T12:00:00Z").getEpochSecond(),
                "admin-1", "Demo Admin", List.of("ADMIN"), "stores", "PUT /api/v1/stores/{storeId}", "/api/v1/stores/s1", 200,
                "{\"name\":\"Spice Route\"}", "{\"name\":\"Spice\"}", "{\"name\":\"Spice Route\"}", "10.0.0.1", null);
        given(this.log.page("admin-1", null, "stores", "e9", 1)).willReturn(new AuditPage(List.of(line), "e2"));

        this.mvc.perform(get("/api/v1/audit").param("actorId", "admin-1").param("q", "stores").param("before", "e9")
            .param("limit", "1")
            .with(jwt().authorities(new SimpleGrantedAuthority("identity:audit:read"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].actorName").value("Demo Admin"))
            .andExpect(jsonPath("$.items[0].at").value(1_793_966_400L))
            .andExpect(jsonPath("$.items[0].before").value("{\"name\":\"Spice\"}"))
            .andExpect(jsonPath("$.nextBefore").value("e2"));
        this.mvc.perform(get("/api/v1/audit").with(jwt().authorities(new SimpleGrantedAuthority("store:portal"))))
            .andExpect(status().isForbidden());
    }
}
