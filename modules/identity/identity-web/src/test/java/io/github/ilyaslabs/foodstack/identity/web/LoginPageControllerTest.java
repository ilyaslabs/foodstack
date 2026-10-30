package io.github.ilyaslabs.foodstack.identity.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.DefaultCsrfToken;

import static org.assertj.core.api.Assertions.assertThat;

class LoginPageControllerTest {

    private final LoginPageController controller = new LoginPageController();

    @Test
    @DisplayName("renders the login form with the CSRF token, escaped, and a strict content security policy")
    void rendersForm() {
        ResponseEntity<String> page = this.controller.login(new DefaultCsrfToken("X-CSRF", "_csrf", "tok\"<en>"), null, null);

        assertThat(page.getBody()).contains("name=\"_csrf\" value=\"tok&quot;&lt;en&gt;\"")
            .contains("action=\"/login\"")
            .doesNotContain("{{");
        assertThat(page.getHeaders().getFirst("Content-Security-Policy")).contains("script-src 'self'")
            .contains("frame-ancestors 'none'");
        assertThat(page.getHeaders().getCacheControl()).isEqualTo("no-store");
    }

    @Test
    @DisplayName("tells the user when the credentials were wrong")
    void error() {
        ResponseEntity<String> page = this.controller.login(new DefaultCsrfToken("X-CSRF", "_csrf", "t"), "", null);

        assertThat(page.getBody()).contains("role=\"alert\"");
    }

    @Test
    @DisplayName("serves the branding script that themes the page")
    void script() {
        assertThat(this.controller.script().getBody()).contains("/api/v1/settings/branding");
    }
}
