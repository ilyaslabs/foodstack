package io.github.ilyaslabs.foodstack.identity.web;

import io.github.ilyaslabs.foodstack.identity.api.events.EmailCodeIssued;
import io.github.ilyaslabs.foodstack.identity.core.application.EmailCodeService;
import io.github.ilyaslabs.foodstack.identity.core.application.UserService;
import io.github.ilyaslabs.foodstack.platform.core.TooManyRequestsException;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.HtmlUtils;

/**
 * Signing in with an emailed code and resetting a forgotten password, as steps of the hosted login
 * page (TDD 9.1, T2): the apps keep the standard authorization code flow. The answer to "send me a
 * code" is the same whether or not the email has an account.
 */
@Controller
class EmailCodePagesController {

    private static final String WRONG_CODE = "<p class=\"message error\" role=\"alert\">That code is not right or has "
            + "expired. Check the latest email, or ask for a new code.</p>";

    private final String template = LoginPageController.read("/identity/code.html");

    private final EmailCodeService codes;

    private final UserService users;

    private final LoginSessions sessions;

    EmailCodePagesController(EmailCodeService codes, UserService users, UserDetailsService identityUserDetailsService) {
        this.codes = codes;
        this.users = users;
        this.sessions = new LoginSessions(identityUserDetailsService);
    }

    @GetMapping(value = "/login/code", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> codeRequestPage(CsrfToken csrf) {
        return LoginPageController.html(HttpStatus.OK, requestPage(csrf, EmailCodeIssued.LOGIN, "", ""));
    }

    @PostMapping(value = "/login/code", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> sendLoginCode(CsrfToken csrf, @RequestParam(defaultValue = "") String email) {
        return send(csrf, EmailCodeIssued.LOGIN, email);
    }

    @PostMapping(value = "/login/code/verify", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> verifyLoginCode(CsrfToken csrf, @RequestParam(defaultValue = "") String email,
            @RequestParam(defaultValue = "") String code, HttpServletRequest request, HttpServletResponse response) {
        if (this.codes.verify(email, EmailCodeIssued.LOGIN, code).isEmpty()) {
            return LoginPageController.html(HttpStatus.BAD_REQUEST, verifyPage(csrf, EmailCodeIssued.LOGIN, email, WRONG_CODE, ""));
        }
        return this.sessions.signInAndContinue(email, FactorGrantedAuthority.OTT_AUTHORITY, "/login?signedin", request,
                response);
    }

    @GetMapping(value = "/login/reset", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> resetRequestPage(CsrfToken csrf) {
        return LoginPageController.html(HttpStatus.OK, requestPage(csrf, EmailCodeIssued.PASSWORD_RESET, "", ""));
    }

    @PostMapping(value = "/login/reset", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> sendResetCode(CsrfToken csrf, @RequestParam(defaultValue = "") String email) {
        return send(csrf, EmailCodeIssued.PASSWORD_RESET, email);
    }

    @PostMapping(value = "/login/reset/verify", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> resetPassword(CsrfToken csrf, @RequestParam(defaultValue = "") String email,
            @RequestParam(defaultValue = "") String code, @RequestParam(defaultValue = "") String password,
            HttpServletRequest request, HttpServletResponse response) {
        // check the new password first, so a too short one does not use the code up
        try {
            UserService.requirePassword(password);
        }
        catch (ModuleException.BadRequest ex) {
            return LoginPageController.html(HttpStatus.BAD_REQUEST, verifyPage(csrf, EmailCodeIssued.PASSWORD_RESET, email, "",
                    "<p class=\"field-error\" role=\"alert\">Use 10 to 128 characters</p>"));
        }
        String userId = this.codes.verify(email, EmailCodeIssued.PASSWORD_RESET, code).orElse(null);
        if (userId == null) {
            return LoginPageController.html(HttpStatus.BAD_REQUEST,
                    verifyPage(csrf, EmailCodeIssued.PASSWORD_RESET, email, WRONG_CODE, ""));
        }
        this.users.resetPassword(userId, password);
        return this.sessions.signInAndContinue(email, FactorGrantedAuthority.PASSWORD_AUTHORITY, "/login?reset", request,
                response);
    }

    private ResponseEntity<String> send(CsrfToken csrf, String purpose, String email) {
        if (email.isBlank() || !email.contains("@")) {
            return LoginPageController.html(HttpStatus.BAD_REQUEST, requestPage(csrf, purpose, email,
                    "<p class=\"field-error\" role=\"alert\">Enter a valid email address</p>"));
        }
        try {
            this.codes.issue(email, purpose);
        }
        catch (TooManyRequestsException ex) {
            long minutes = Math.max(1, (ex.retryAfterSeconds() + 59) / 60);
            String message = "<p class=\"message error\" role=\"alert\">Too many codes were asked for this email. Try again in "
                    + minutes + ((minutes == 1) ? " minute" : " minutes") + ".</p>";
            return LoginPageController.html(HttpStatus.TOO_MANY_REQUESTS, page(csrf, title(purpose), intro(purpose),
                    message, action(purpose), emailField(email, ""), "Send code", backToSignIn()));
        }
        return LoginPageController.html(HttpStatus.OK, verifyPage(csrf, purpose, email, "", ""));
    }

    private String requestPage(CsrfToken csrf, String purpose, String email, String emailError) {
        return page(csrf, title(purpose), intro(purpose), "", action(purpose), emailField(email, emailError), "Send code",
                backToSignIn());
    }

    private String verifyPage(CsrfToken csrf, String purpose, String email, String message, String passwordError) {
        boolean reset = EmailCodeIssued.PASSWORD_RESET.equals(purpose);
        String safeEmail = HtmlUtils.htmlEscape(email.trim());
        String intro = "If <strong>" + safeEmail + "</strong> has an account, we have emailed it a 6-digit code. "
                + "It works for " + EmailCodeService.TTL.toMinutes() + " minutes.";
        StringBuilder fields = new StringBuilder()
            .append("<input type=\"hidden\" name=\"email\" value=\"").append(safeEmail).append("\">")
            .append("<label for=\"code\">Code</label>")
            .append("<input id=\"code\" name=\"code\" class=\"code-input\" type=\"text\" inputmode=\"numeric\" ")
            .append("autocomplete=\"one-time-code\" pattern=\"[0-9]{6}\" maxlength=\"6\" required autofocus>");
        if (reset) {
            fields.append("<label for=\"password\">New password</label>")
                .append("<input id=\"password\" name=\"password\" type=\"password\" autocomplete=\"new-password\" ")
                .append("minlength=\"10\" maxlength=\"128\" required aria-describedby=\"password-hint\">")
                .append("<p class=\"hint\" id=\"password-hint\">At least 10 characters</p>")
                .append(passwordError);
        }
        String footer = "<p class=\"switch\">No email? <a href=\"" + action(purpose) + "\">Send a new code</a></p>";
        return page(csrf, reset ? "Choose a new password" : "Enter your code", intro, message, action(purpose) + "/verify",
                fields.toString(), reset ? "Save password and sign in" : "Sign in", footer);
    }

    private String page(CsrfToken csrf, String title, String intro, String message, String action, String fields,
            String button, String footer) {
        return this.template.replace("{{csrfParameter}}", HtmlUtils.htmlEscape(csrf.getParameterName()))
            .replace("{{csrfToken}}", HtmlUtils.htmlEscape(csrf.getToken()))
            .replace("{{pageTitle}}", title)
            .replace("{{intro}}", intro)
            .replace("{{message}}", message)
            .replace("{{action}}", action)
            .replace("{{fields}}", fields)
            .replace("{{button}}", button)
            .replace("{{footer}}", footer);
    }

    private static String emailField(String email, String error) {
        return "<label for=\"email\">Email</label><input id=\"email\" name=\"email\" type=\"email\" autocomplete=\"email\" "
                + "required autofocus value=\"" + HtmlUtils.htmlEscape(email) + "\">" + error;
    }

    private static String title(String purpose) {
        return EmailCodeIssued.LOGIN.equals(purpose) ? "Sign in with a code" : "Reset your password";
    }

    private static String intro(String purpose) {
        return EmailCodeIssued.LOGIN.equals(purpose) ? "We will email you a code, so you can sign in without a password"
                : "We will email you a code to choose a new password";
    }

    private static String action(String purpose) {
        return EmailCodeIssued.LOGIN.equals(purpose) ? "/login/code" : "/login/reset";
    }

    private static String backToSignIn() {
        return "<p class=\"switch\"><a href=\"/login\">Back to sign in</a></p>";
    }
}
