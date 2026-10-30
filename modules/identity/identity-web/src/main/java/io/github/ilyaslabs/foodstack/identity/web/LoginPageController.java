package io.github.ilyaslabs.foodstack.identity.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.HtmlUtils;

/**
 * The hosted login page of the authorization server (TDD 9.1). It themes itself at runtime from
 * {@code /api/v1/settings/branding}, served by whichever deployment runs settings.
 */
@Controller
class LoginPageController {

    private final String template = read("/identity/login.html");

    private final String script = read("/identity/login.js");

    @GetMapping(value = "/login", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> login(CsrfToken csrf, @RequestParam(required = false) String error,
            @RequestParam(required = false) String logout) {
        String message = "";
        if (error != null) {
            message = "<p class=\"message error\" role=\"alert\">The email or password is not correct.</p>";
        }
        else if (logout != null) {
            message = "<p class=\"message\" role=\"status\">You are signed out.</p>";
        }
        String page = this.template.replace("{{csrfParameter}}", HtmlUtils.htmlEscape(csrf.getParameterName()))
            .replace("{{csrfToken}}", HtmlUtils.htmlEscape(csrf.getToken()))
            .replace("{{message}}", message);
        return ResponseEntity.ok()
            .header("Content-Security-Policy",
                    "default-src 'none'; script-src 'self'; style-src 'unsafe-inline'; "
                            + "img-src * data:; connect-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'")
            .header("Cache-Control", "no-store")
            .body(page);
    }

    @GetMapping(value = "/login/branding.js", produces = "text/javascript")
    ResponseEntity<String> script() {
        return ResponseEntity.ok().header("Cache-Control", "public, max-age=300").body(this.script);
    }

    private static String read(String path) {
        try (InputStream in = LoginPageController.class.getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
