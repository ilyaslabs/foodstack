package io.github.ilyaslabs.foodstack.identity.web;

import java.util.List;
import java.util.Optional;

import io.github.ilyaslabs.foodstack.identity.api.events.EmailCodeIssued;
import io.github.ilyaslabs.foodstack.identity.core.application.EmailCodeService;
import io.github.ilyaslabs.foodstack.identity.core.application.UserService;
import io.github.ilyaslabs.foodstack.platform.core.TooManyRequestsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.DefaultCsrfToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class EmailCodePagesControllerTest {

    private static final DefaultCsrfToken CSRF = new DefaultCsrfToken("X-CSRF", "_csrf", "t");

    private final EmailCodeService codes = mock(EmailCodeService.class);

    private final UserService users = mock(UserService.class);

    private final UserDetailsService userDetails = mock(UserDetailsService.class);

    private final EmailCodePagesController controller = new EmailCodePagesController(this.codes, this.users, this.userDetails);

    @Test
    @DisplayName("asking for a code always shows the code form, without saying whether the email has an account")
    void sendCode() {
        ResponseEntity<String> page = this.controller.sendLoginCode(CSRF, "a<b>@example.com");

        verify(this.codes).issue("a<b>@example.com", EmailCodeIssued.LOGIN);
        assertThat(page.getStatusCode().value()).isEqualTo(200);
        assertThat(page.getBody()).contains("If <strong>a&lt;b&gt;@example.com</strong> has an account")
            .contains("action=\"/login/code/verify\"")
            .contains("autocomplete=\"one-time-code\"")
            .doesNotContain("name=\"password\"")
            .doesNotContain("{{");
    }

    @Test
    @DisplayName("too many codes for one email are refused with the wait time")
    void tooMany() {
        willThrow(new TooManyRequestsException("slow down", 600)).given(this.codes).issue("a@example.com", EmailCodeIssued.LOGIN);

        ResponseEntity<String> page = this.controller.sendLoginCode(CSRF, "a@example.com");

        assertThat(page.getStatusCode().value()).isEqualTo(429);
        assertThat(page.getBody()).contains("Try again in 10 minutes");
    }

    @Test
    @DisplayName("a right code signs the user in with the one-time-code factor and continues the sign-in")
    void verifyCode() {
        given(this.codes.verify("a@example.com", EmailCodeIssued.LOGIN, "123456")).willReturn(Optional.of("u1"));
        given(this.userDetails.loadUserByUsername("a@example.com"))
            .willReturn(new User("u1", "x", List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));
        MockHttpServletRequest request = new MockHttpServletRequest();

        ResponseEntity<String> result = this.controller.verifyLoginCode(CSRF, "a@example.com", "123456", request,
                new MockHttpServletResponse());

        assertThat(result.getStatusCode().value()).isEqualTo(303);
        assertThat(result.getHeaders().getLocation()).hasToString("/login?signedin");
        SecurityContext saved = (SecurityContext) request.getSession()
            .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(saved.getAuthentication().getName()).isEqualTo("u1");
        assertThat(saved.getAuthentication().getAuthorities()).extracting("authority").contains(FactorGrantedAuthority.OTT_AUTHORITY);
    }

    @Test
    @DisplayName("a wrong code shows the form again with an error and signs nobody in")
    void wrongCode() {
        given(this.codes.verify(any(), any(), any())).willReturn(Optional.empty());
        MockHttpServletRequest request = new MockHttpServletRequest();

        ResponseEntity<String> page = this.controller.verifyLoginCode(CSRF, "a@example.com", "000000", request,
                new MockHttpServletResponse());

        assertThat(page.getStatusCode().value()).isEqualTo(400);
        assertThat(page.getBody()).contains("not right or has expired").contains("value=\"a@example.com\"");
        assertThat(request.getSession(false)).isNull();
    }

    @Test
    @DisplayName("a reset checks the new password before using the code, then saves it and signs the user in")
    void reset() {
        ResponseEntity<String> tooShort = this.controller.resetPassword(CSRF, "a@example.com", "123456", "short",
                new MockHttpServletRequest(), new MockHttpServletResponse());
        assertThat(tooShort.getStatusCode().value()).isEqualTo(400);
        assertThat(tooShort.getBody()).contains("Use 10 to 128 characters").contains("name=\"password\"");
        verify(this.codes, never()).verify(any(), any(), any());

        given(this.codes.verify("a@example.com", EmailCodeIssued.PASSWORD_RESET, "123456")).willReturn(Optional.of("u1"));
        given(this.userDetails.loadUserByUsername("a@example.com"))
            .willReturn(new User("u1", "x", List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));
        ResponseEntity<String> done = this.controller.resetPassword(CSRF, "a@example.com", "123456", "a new long password",
                new MockHttpServletRequest(), new MockHttpServletResponse());

        verify(this.users).resetPassword("u1", "a new long password");
        assertThat(done.getStatusCode().value()).isEqualTo(303);
        assertThat(done.getHeaders().getLocation()).hasToString("/login?reset");
    }
}
