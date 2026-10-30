package io.github.ilyaslabs.foodstack.platform.security;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ApiSecurityConfigurationTest.SampleController.class)
@Import({ ApiSecurityConfiguration.class, ApiSecurityConfigurationTest.SampleController.class })
class ApiSecurityConfigurationTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {

        @Bean
        PublicEndpoints samplePublicEndpoints() {
            return PublicEndpoints.of(HttpMethod.GET, "/api/v1/public");
        }
    }

    @RestController
    static class SampleController {

        @GetMapping("/api/v1/public")
        String open() {
            return "open";
        }

        @GetMapping("/api/v1/me")
        String me() {
            return CurrentUser.id();
        }

        @GetMapping("/api/v1/admin")
        @PreAuthorize("hasAuthority('settings:manage')")
        String admin() {
            return "admin";
        }

        @GetMapping("/internal")
        String internal() {
            return "internal";
        }
    }

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    MockMvc mvc;

    @Test
    @DisplayName("serves declared public endpoints without a token")
    void publicEndpoint() throws Exception {
        this.mvc.perform(get("/api/v1/public")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("requires a token everywhere else under /api")
    void protectedEndpoint() throws Exception {
        this.mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
        this.mvc.perform(get("/api/v1/me").with(jwt().jwt((t) -> t.subject("user-7"))))
            .andExpect(status().isOk())
            .andExpect(content().string("user-7"));
    }

    @Test
    @DisplayName("checks permissions from the token")
    void permissions() throws Exception {
        this.mvc.perform(get("/api/v1/admin").with(jwt())).andExpect(status().isForbidden());
        this.mvc.perform(get("/api/v1/admin").with(jwt().authorities(List.of(new SimpleGrantedAuthority("settings:manage")))))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("denies paths outside the API that no module claims")
    void outsideApi() throws Exception {
        this.mvc.perform(get("/internal").with(jwt())).andExpect(status().isForbidden());
    }
}
