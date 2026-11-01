package io.github.ilyaslabs.foodstack.identity.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import io.github.ilyaslabs.foodstack.identity.core.application.UserService;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.HtmlUtils;

/**
 * The hosted login and sign-up pages of the authorization server (TDD 9.1). They theme themselves
 * at runtime from {@code /api/v1/settings/branding}, served by whichever deployment runs settings.
 * Sign-up is offered only when the sign-in started from the customer app (PRD ID-1): back-office
 * accounts are created by admins. A new customer is signed in straight away and sent on to the app.
 */
@Controller
class LoginPageController {

    /** The customer app's OAuth client (deployment guide 2.1). */
    static final String CUSTOMER_CLIENT = "foodstack-web";

    /** Extra authorize parameter the customer app adds for its "Create account" button. */
    static final String SCREEN_PARAMETER = "fs_screen";

    private static final String CSP = "default-src 'none'; script-src 'self'; style-src 'unsafe-inline'; "
            + "img-src * data:; connect-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'";

    private final String loginTemplate = read("/identity/login.html");

    private final String signupTemplate = read("/identity/signup.html");

    private final String script = read("/identity/login.js");

    private final HttpSessionRequestCache requestCache = new HttpSessionRequestCache();

    private final HttpSessionSecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    private final UserService users;

    private final UserDetailsService userDetails;

    LoginPageController(UserService users, UserDetailsService identityUserDetailsService) {
        this.users = users;
        this.userDetails = identityUserDetailsService;
    }

    @GetMapping(value = "/login", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> login(CsrfToken csrf, @RequestParam(required = false) String error,
            @RequestParam(required = false) String logout, HttpServletRequest request, HttpServletResponse response) {
        SavedRequest saved = this.requestCache.getRequest(request, response);
        boolean customer = fromCustomerApp(saved);
        if (customer && error == null && logout == null && "signup".equals(parameter(saved, SCREEN_PARAMETER))) {
            return ResponseEntity.status(HttpStatus.FOUND).header("Location", "/login/signup").build();
        }
        String message = "";
        if (error != null) {
            message = "<p class=\"message error\" role=\"alert\">The email or password is not correct.</p>";
        }
        else if (logout != null) {
            message = "<p class=\"message\" role=\"status\">You are signed out.</p>";
        }
        String signup = customer
                ? "<p class=\"switch\">New here? <a href=\"/login/signup\">Create an account</a></p>" : "";
        String page = this.loginTemplate.replace("{{csrfParameter}}", HtmlUtils.htmlEscape(csrf.getParameterName()))
            .replace("{{csrfToken}}", HtmlUtils.htmlEscape(csrf.getToken()))
            .replace("{{message}}", message)
            .replace("{{signup}}", signup);
        return html(HttpStatus.OK, page);
    }

    @GetMapping(value = "/login/signup", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> signupPage(CsrfToken csrf, HttpServletRequest request, HttpServletResponse response) {
        if (!fromCustomerApp(this.requestCache.getRequest(request, response))) {
            return ResponseEntity.status(HttpStatus.FOUND).header("Location", "/login").build();
        }
        return html(HttpStatus.OK, signupPage(csrf, "", "", "", Map.of()));
    }

    @PostMapping(value = "/login/signup", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> signup(CsrfToken csrf, @RequestParam(defaultValue = "") String displayName,
            @RequestParam(defaultValue = "") String email, @RequestParam(defaultValue = "") String password,
            HttpServletRequest request, HttpServletResponse response) {
        SavedRequest saved = this.requestCache.getRequest(request, response);
        if (!fromCustomerApp(saved)) {
            return ResponseEntity.status(HttpStatus.FOUND).header("Location", "/login").build();
        }
        try {
            this.users.register(email, password, displayName);
        }
        catch (ModuleException.Conflict ex) {
            String message = "<p class=\"message error\" role=\"alert\">An account with this email already exists. "
                    + "<a href=\"/login\">Sign in</a> instead.</p>";
            return html(HttpStatus.CONFLICT, signupPage(csrf, message, displayName, email, Map.of()));
        }
        catch (ModuleException.BadRequest ex) {
            return html(HttpStatus.BAD_REQUEST, signupPage(csrf, "", displayName, email, fieldErrors(ex)));
        }
        // Sign the new customer in, as the login form would, and continue the authorization request
        UserDetails user = this.userDetails.loadUserByUsername(email.trim());
        request.changeSessionId();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
        SecurityContextHolder.setContext(context);
        this.contextRepository.saveContext(context, request, response);
        return ResponseEntity.status(HttpStatus.SEE_OTHER).header("Location", saved.getRedirectUrl()).build();
    }

    @GetMapping(value = "/login/branding.js", produces = "text/javascript")
    ResponseEntity<String> script() {
        return ResponseEntity.ok().header("Cache-Control", "public, max-age=300").body(this.script);
    }

    private String signupPage(CsrfToken csrf, String message, String displayName, String email, Map<String, String> errors) {
        return this.signupTemplate.replace("{{csrfParameter}}", HtmlUtils.htmlEscape(csrf.getParameterName()))
            .replace("{{csrfToken}}", HtmlUtils.htmlEscape(csrf.getToken()))
            .replace("{{message}}", message)
            .replace("{{displayName}}", HtmlUtils.htmlEscape(displayName))
            .replace("{{email}}", HtmlUtils.htmlEscape(email))
            .replace("{{displayNameError}}", fieldError(errors.get("displayName"), "Enter your name (up to 80 characters)"))
            .replace("{{emailError}}", fieldError(errors.get("email"), "Enter a valid email address"))
            .replace("{{passwordError}}", fieldError(errors.get("password"), "Use 10 to 128 characters"));
    }

    private static String fieldError(String error, String text) {
        return (error != null) ? "<p class=\"field-error\" role=\"alert\">" + text + "</p>" : "";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> fieldErrors(ModuleException ex) {
        Object errors = ex.details().get("errors");
        return (errors instanceof Map<?, ?> map) ? (Map<String, String>) map : Map.of();
    }

    private static boolean fromCustomerApp(SavedRequest saved) {
        return CUSTOMER_CLIENT.equals(parameter(saved, "client_id"));
    }

    private static String parameter(SavedRequest saved, String name) {
        if (saved == null) {
            return null;
        }
        String[] values = saved.getParameterValues(name);
        return (values != null && values.length > 0) ? values[0] : null;
    }

    private static ResponseEntity<String> html(HttpStatus status, String page) {
        return ResponseEntity.status(status)
            .header("Content-Security-Policy", CSP)
            .header("Cache-Control", "no-store")
            .contentType(MediaType.TEXT_HTML)
            .body(page);
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
