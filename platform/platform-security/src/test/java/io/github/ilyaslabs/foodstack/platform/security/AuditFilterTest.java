package io.github.ilyaslabs.foodstack.platform.security;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import io.github.ilyaslabs.foodstack.platform.core.AuditEntry;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.servlet.HandlerMapping;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class AuditFilterTest {

    private final List<AuditEntry> recorded = new ArrayList<>();

    private final AuditFilter filter = new AuditFilter(this.recorded::add, JsonMapper.builder().build(),
            Clock.fixed(Instant.parse("2026-11-06T12:00:00Z"), ZoneOffset.UTC));

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("records an operator's successful write with the endpoint, the body sent and answered, and secrets masked")
    void recordsWrite() throws Exception {
        signIn("admin-1", "ROLE_ADMIN", "store:manage");
        MockHttpServletRequest request = write("PUT", "/api/v1/stores/s1",
                "{\"name\":\"Spice Route\",\"owner\":{\"password\":\"hunter2\"},\"apiKey\":\"sk_live\"}");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/stores/{storeId}");
        MockHttpServletResponse response = new MockHttpServletResponse();

        this.filter.doFilter(request, response, answering(200, "{\"id\":\"s1\",\"version\":3}"));

        assertThat(response.getContentAsString()).isEqualTo("{\"id\":\"s1\",\"version\":3}");
        assertThat(this.recorded).hasSize(1);
        AuditEntry entry = this.recorded.getFirst();
        assertThat(entry.actorId()).isEqualTo("admin-1");
        assertThat(entry.roles()).containsExactly("ADMIN");
        assertThat(entry.area()).isEqualTo("stores");
        assertThat(entry.action()).isEqualTo("PUT /api/v1/stores/{storeId}");
        assertThat(entry.resource()).isEqualTo("/api/v1/stores/s1");
        assertThat(entry.request()).contains("\"name\":\"Spice Route\"").contains("\"password\":\"***\"")
            .contains("\"apiKey\":\"***\"").doesNotContain("hunter2").doesNotContain("sk_live");
        assertThat(entry.after()).isEqualTo("{\"id\":\"s1\",\"version\":3}");
        assertThat(entry.at()).isEqualTo(Instant.parse("2026-11-06T12:00:00Z"));
    }

    @Test
    @DisplayName("skips reads, refused writes, sign-in calls, customers and riders")
    void skips() throws Exception {
        signIn("owner-1", "ROLE_STORE_OWNER", "store:portal");
        this.filter.doFilter(write("GET", "/api/v1/stores/s1", null), new MockHttpServletResponse(), answering(200, "{}"));
        this.filter.doFilter(write("PUT", "/api/v1/stores/s1", "{}"), new MockHttpServletResponse(), answering(403, "{}"));
        this.filter.doFilter(write("POST", "/api/v1/auth/logout", "{}"), new MockHttpServletResponse(), answering(204, ""));
        signIn("customer-1", "ROLE_CUSTOMER");
        this.filter.doFilter(write("POST", "/api/v1/orders", "{}"), new MockHttpServletResponse(), answering(201, "{}"));
        signIn("rider-1", "ROLE_RIDER", "rider:portal");
        this.filter.doFilter(write("POST", "/api/v1/rider/me/locations", "{}"), new MockHttpServletResponse(),
                answering(204, ""));

        assertThat(this.recorded).isEmpty();
    }

    @Test
    @DisplayName("a store owner's write is recorded; a body that is not JSON is left out")
    void storeOwner() throws Exception {
        signIn("owner-1", "ROLE_STORE_OWNER", "store:portal");
        MockHttpServletRequest request = write("POST", "/api/v1/stores/s1/close", null);
        this.filter.doFilter(request, new MockHttpServletResponse(), answering(204, ""));

        assertThat(this.recorded).singleElement().satisfies((entry) -> {
            assertThat(entry.action()).isEqualTo("POST /api/v1/stores/s1/close");
            assertThat(entry.request()).isNull();
            assertThat(entry.after()).isNull();
        });
    }

    private static MockHttpServletRequest write(String method, String path, String json) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (json != null) {
            request.setContentType("application/json");
            request.setContent(json.getBytes());
        }
        return request;
    }

    /** A chain whose handler reads the body, as a controller would, and answers. */
    private static MockFilterChain answering(int status, String body) {
        return new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void service(jakarta.servlet.http.HttpServletRequest req, HttpServletResponse res)
                    throws java.io.IOException {
                req.getInputStream().readAllBytes();
                res.setStatus(status);
                if (!body.isEmpty()) {
                    res.setContentType("application/json");
                    res.getWriter().write(body);
                }
            }
        });
    }

    private static void signIn(String subject, String... authorities) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(subject).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
                java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList(), subject));
    }
}
