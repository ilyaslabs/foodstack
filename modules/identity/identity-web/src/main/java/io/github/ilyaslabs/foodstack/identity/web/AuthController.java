package io.github.ilyaslabs.foodstack.identity.web;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

import io.github.ilyaslabs.foodstack.identity.api.events.EmailCodeIssued;
import io.github.ilyaslabs.foodstack.identity.core.application.EmailCodeService;
import io.github.ilyaslabs.foodstack.identity.core.application.SessionService;
import io.github.ilyaslabs.foodstack.identity.core.application.SessionService.Client;
import io.github.ilyaslabs.foodstack.identity.core.application.SessionService.Session;
import io.github.ilyaslabs.foodstack.identity.core.application.UserService;
import io.github.ilyaslabs.foodstack.identity.web.dto.CodeRequest;
import io.github.ilyaslabs.foodstack.identity.web.dto.LoginRequest;
import io.github.ilyaslabs.foodstack.identity.web.dto.RefreshRequest;
import io.github.ilyaslabs.foodstack.identity.web.dto.RegisterRequest;
import io.github.ilyaslabs.foodstack.identity.web.dto.ResetPasswordRequest;
import io.github.ilyaslabs.foodstack.identity.web.dto.TokenResponse;
import io.github.ilyaslabs.foodstack.identity.web.dto.UserResponse;
import io.github.ilyaslabs.foodstack.platform.security.CurrentUser;
import io.github.ilyaslabs.foodstack.platform.web.ProblemDetailsAdvice;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import jakarta.validation.Valid;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stateless sign-in (TDD 9.1): sign up, sign in with a password or an emailed code, reset a
 * password, refresh, sign out. No server session and no redirect. Browsers ({@code client=web})
 * get the refresh token as an httpOnly, Secure, SameSite=Strict cookie scoped to the refresh path,
 * so scripts never see it; mobile apps ({@code client=mobile}) get it in the body.
 */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    static final String REFRESH_PATH = "/api/v1/auth/refresh";

    static final String REFRESH_COOKIE = "fs_refresh";

    private final UserService userService;

    private final SessionService sessions;

    private final EmailCodeService codes;

    private final boolean secureCookie;

    AuthController(UserService userService, SessionService sessions, EmailCodeService codes,
            @Value("${foodstack.identity.refresh-cookie-secure:true}") boolean secureCookie) {
        this.userService = userService;
        this.sessions = sessions;
        this.codes = codes;
        this.secureCookie = secureCookie;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return IdentityWebMapper.toResponse(this.userService.register(request.email(), request.password(), request.displayName()));
    }

    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        Client client = Client.parse(request.client());
        boolean withCode = request.code() != null && !request.code().isBlank();
        boolean withPassword = request.password() != null && !request.password().isEmpty();
        if (withCode == withPassword) {
            throw new ModuleException.BadRequest("Send either a password or a code", "LOGIN_INVALID",
                    Map.of("errors", Map.of("password", "send a password or a code")));
        }
        Session session = withCode ? this.sessions.signInWithCode(request.email(), request.code(), client)
                : this.sessions.signInWithPassword(request.email(), request.password(), client);
        return tokens(session);
    }

    /** Emails a code; the answer is the same whether or not the email has an account. */
    @PostMapping(value = "/code", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    void code(@Valid @RequestBody CodeRequest request) {
        String purpose = request.purpose().trim().toUpperCase(Locale.ROOT);
        if (!EmailCodeIssued.LOGIN.equals(purpose) && !EmailCodeIssued.PASSWORD_RESET.equals(purpose)) {
            throw new ModuleException.BadRequest("purpose must be LOGIN or PASSWORD_RESET", "CODE_PURPOSE_INVALID");
        }
        this.codes.issue(request.email().trim(), purpose);
    }

    @PostMapping(value = "/reset", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<TokenResponse> reset(@Valid @RequestBody ResetPasswordRequest request) {
        Client client = Client.parse(request.client());
        return tokens(this.sessions.resetPassword(request.email(), request.code(), request.newPassword(), client));
    }

    /**
     * Swaps the refresh token for new tokens. Browsers send the cookie (and an empty JSON body),
     * mobile apps the body; both at once is refused. A JSON body is required, so a plain form post
     * from another site cannot trigger it.
     */
    @PostMapping(value = "/refresh", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> refresh(@CookieValue(name = REFRESH_COOKIE, required = false) String cookie,
            @RequestBody(required = false) RefreshRequest body) {
        String fromBody = (body != null) ? body.refreshToken() : null;
        boolean hasCookie = cookie != null && !cookie.isBlank();
        boolean hasBody = fromBody != null && !fromBody.isBlank();
        if (hasCookie && hasBody) {
            throw new ModuleException.BadRequest("Send the refresh token in the cookie or the body, not both",
                    "REFRESH_TWO_CHANNELS");
        }
        if (!hasCookie && !hasBody) {
            throw new ModuleException.Unauthorized("No refresh token; sign in again", "REFRESH_MISSING");
        }
        try {
            return tokens(hasCookie ? this.sessions.refresh(cookie, Client.WEB) : this.sessions.refresh(fromBody, Client.MOBILE));
        }
        catch (ModuleException.Unauthorized ex) {
            if (!hasCookie) {
                throw ex;
            }
            // a dead cookie is cleared, so the browser stops sending it
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.SET_COOKIE, clearCookie().toString())
                .body(ProblemDetailsAdvice.problem(HttpStatus.UNAUTHORIZED, ex.getMessage(), ex.code()));
        }
    }

    /** Ends this sign-in (the {@code sid} of the access token) and clears the browser's cookie. */
    @PostMapping("/logout")
    ResponseEntity<Void> logout() {
        this.sessions.end(CurrentUser.id(), CurrentUser.sessionId());
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, clearCookie().toString()).build();
    }

    private ResponseEntity<TokenResponse> tokens(Session session) {
        long refreshExpiresAt = session.refreshExpiresAt().getEpochSecond();
        ResponseEntity.BodyBuilder response = ResponseEntity.ok().cacheControl(CacheControl.noStore());
        if (session.client() == Client.WEB) {
            Duration maxAge = Duration.between(Instant.now(), session.refreshExpiresAt());
            response.header(HttpHeaders.SET_COOKIE, cookie(session.refreshToken(), maxAge).toString());
            return response.body(new TokenResponse(session.accessToken(), "Bearer", session.expiresIn(), null,
                    refreshExpiresAt));
        }
        return response.body(new TokenResponse(session.accessToken(), "Bearer", session.expiresIn(),
                session.refreshToken(), refreshExpiresAt));
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
            .httpOnly(true)
            .secure(this.secureCookie)
            .sameSite("Strict")
            .path(REFRESH_PATH)
            .maxAge(maxAge)
            .build();
    }

    private ResponseCookie clearCookie() {
        return cookie("", Duration.ZERO);
    }
}
