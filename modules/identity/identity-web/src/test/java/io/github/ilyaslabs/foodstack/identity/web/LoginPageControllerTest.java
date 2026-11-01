package io.github.ilyaslabs.foodstack.identity.web;

import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.identity.core.application.UserService;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class LoginPageControllerTest {

    private static final DefaultCsrfToken CSRF = new DefaultCsrfToken("X-CSRF", "_csrf", "t");

    private final UserService users = mock(UserService.class);

    private final UserDetailsService userDetails = mock(UserDetailsService.class);

    private final LoginPageController controller = new LoginPageController(this.users, this.userDetails);

    @Test
    @DisplayName("renders the login form with the CSRF token, escaped, and a strict content security policy")
    void rendersForm() {
        ResponseEntity<String> page = this.controller.login(new DefaultCsrfToken("X-CSRF", "_csrf", "tok\"<en>"), null, null,
                new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(page.getBody()).contains("name=\"_csrf\" value=\"tok&quot;&lt;en&gt;\"")
            .contains("action=\"/login\"")
            .doesNotContain("{{")
            .doesNotContain("/login/signup");
        assertThat(page.getHeaders().getFirst("Content-Security-Policy")).contains("script-src 'self'")
            .contains("frame-ancestors 'none'");
        assertThat(page.getHeaders().getCacheControl()).isEqualTo("no-store");
    }

    @Test
    @DisplayName("tells the user when the credentials were wrong")
    void error() {
        ResponseEntity<String> page = this.controller.login(CSRF, "", null, new MockHttpServletRequest(),
                new MockHttpServletResponse());

        assertThat(page.getBody()).contains("role=\"alert\"");
    }

    @Test
    @DisplayName("offers sign-up only to the customer app, and opens it directly when the app asked for it")
    void signupLink() {
        MockHttpServletRequest customer = savedAuthorize(Map.of("client_id", "foodstack-web"));
        assertThat(this.controller.login(CSRF, null, null, customer, new MockHttpServletResponse()).getBody())
            .contains("href=\"/login/signup\"");

        MockHttpServletRequest backOffice = savedAuthorize(Map.of("client_id", "foodstack-backoffice"));
        assertThat(this.controller.login(CSRF, null, null, backOffice, new MockHttpServletResponse()).getBody())
            .doesNotContain("/login/signup");
        assertThat(this.controller.signupPage(CSRF, backOffice, new MockHttpServletResponse()).getHeaders().getLocation())
            .hasToString("/login");

        MockHttpServletRequest straightToSignup = savedAuthorize(Map.of("client_id", "foodstack-web", "fs_screen", "signup"));
        assertThat(this.controller.login(CSRF, null, null, straightToSignup, new MockHttpServletResponse()).getHeaders()
            .getLocation()).hasToString("/login/signup");
    }

    @Test
    @DisplayName("a new customer is signed in at once and sent on with the original authorization request")
    void signup() {
        MockHttpServletRequest request = savedAuthorize(Map.of("client_id", "foodstack-web"));
        given(this.userDetails.loadUserByUsername("new@example.com")).willReturn(
                new User("u9", "x", List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));
        MockHttpServletResponse response = new MockHttpServletResponse();

        ResponseEntity<String> result = this.controller.signup(CSRF, "New Customer", " new@example.com ", "correct horse",
                request, response);

        verify(this.users).register(" new@example.com ", "correct horse", "New Customer");
        assertThat(result.getStatusCode().value()).isEqualTo(303);
        assertThat(result.getHeaders().getLocation().toString()).contains("/oauth2/authorize").contains("client_id=foodstack-web");
        SecurityContext saved = (SecurityContext) request.getSession()
            .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(saved.getAuthentication().getName()).isEqualTo("u9");
        assertThat(saved.getAuthentication().isAuthenticated()).isTrue();
    }

    @Test
    @DisplayName("keeps what was typed and explains a taken email or an invalid field")
    void signupErrors() {
        MockHttpServletRequest request = savedAuthorize(Map.of("client_id", "foodstack-web"));
        willThrow(new ModuleException.Conflict("exists", "EMAIL_TAKEN")).given(this.users)
            .register("a@example.com", "correct horse", "A<b>");

        ResponseEntity<String> taken = this.controller.signup(CSRF, "A<b>", "a@example.com", "correct horse", request,
                new MockHttpServletResponse());

        assertThat(taken.getStatusCode().value()).isEqualTo(409);
        assertThat(taken.getBody()).contains("already exists").contains("value=\"A&lt;b&gt;\"").doesNotContain("{{");

        willThrow(new ModuleException.BadRequest("invalid", "USER_INVALID", Map.of("errors", Map.of("password", "short"))))
            .given(this.users).register("b@example.com", "short", "B");
        ResponseEntity<String> invalid = this.controller.signup(CSRF, "B", "b@example.com", "short", request,
                new MockHttpServletResponse());
        assertThat(invalid.getStatusCode().value()).isEqualTo(400);
        assertThat(invalid.getBody()).contains("Use 10 to 128 characters").doesNotContain("Enter a valid email");
        verify(this.userDetails, never()).loadUserByUsername("b@example.com");
    }

    @Test
    @DisplayName("serves the branding script that themes the page")
    void script() {
        assertThat(this.controller.script().getBody()).contains("/api/v1/settings/branding");
    }

    /** A browser whose authorization request was saved before it was sent to the login page. */
    private static MockHttpServletRequest savedAuthorize(Map<String, String> parameters) {
        MockHttpServletRequest authorize = new MockHttpServletRequest("GET", "/oauth2/authorize");
        authorize.setServerName("localhost");
        parameters.forEach(authorize::addParameter);
        authorize.setQueryString(String.join("&", parameters.entrySet().stream().map((e) -> e.getKey() + "=" + e.getValue()).toList()));
        new HttpSessionRequestCache().saveRequest(authorize, new MockHttpServletResponse());
        MockHttpServletRequest next = new MockHttpServletRequest("GET", "/login");
        next.setSession(authorize.getSession());
        return next;
    }
}
