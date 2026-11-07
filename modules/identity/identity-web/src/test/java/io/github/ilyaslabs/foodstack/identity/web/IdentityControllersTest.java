package io.github.ilyaslabs.foodstack.identity.web;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import io.github.ilyaslabs.foodstack.identity.api.Role;
import io.github.ilyaslabs.foodstack.identity.api.UserStatus;
import io.github.ilyaslabs.foodstack.identity.core.application.AccountDeletion;
import io.github.ilyaslabs.foodstack.identity.core.application.AddressBook;
import io.github.ilyaslabs.foodstack.identity.core.application.EmailCodeService;
import io.github.ilyaslabs.foodstack.identity.core.application.NewUser;
import io.github.ilyaslabs.foodstack.identity.core.application.SavedAddress;
import io.github.ilyaslabs.foodstack.identity.core.application.SessionService;
import io.github.ilyaslabs.foodstack.identity.core.application.SessionService.Client;
import io.github.ilyaslabs.foodstack.identity.core.application.SessionService.Session;
import io.github.ilyaslabs.foodstack.identity.core.application.UserAccount;
import io.github.ilyaslabs.foodstack.identity.core.application.UserDirectory;
import io.github.ilyaslabs.foodstack.identity.core.application.UserService;
import io.github.ilyaslabs.foodstack.platform.security.ApiSecurityConfiguration;
import io.github.ilyaslabs.foodstack.platform.web.ProblemDetailsAdvice;
import io.github.ilyaslabs.moduleswitch.ModuleException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = { AuthController.class, MeController.class, UsersController.class },
        properties = "module-switch.modules.identity.mode=local")
@Import({ ApiSecurityConfiguration.class, ProblemDetailsAdvice.class, IdentityWebConfiguration.class })
class IdentityControllersTest {

    @SpringBootApplication
    static class App {
    }

    @MockitoBean
    UserService userService;

    @MockitoBean
    UserDirectory userDirectory;

    @MockitoBean
    SessionService sessions;

    @MockitoBean
    EmailCodeService codes;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    AddressBook addressBook;

    @MockitoBean
    AccountDeletion accountDeletion;

    @Autowired
    MockMvc mvc;

    @Test
    @DisplayName("POST /api/v1/auth/register signs a customer up without a token")
    void register() throws Exception {
        given(this.userService.register("new@example.com", "correct horse", "New")).willReturn(account("u9", Role.CUSTOMER));

        this.mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"new@example.com\",\"password\":\"correct horse\",\"displayName\":\"New\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value("u9"))
            .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"))
            .andExpect(jsonPath("$.createdAt").value(1_800_000_000L));
    }

    @Test
    @DisplayName("reports an existing email as 409 EMAIL_TAKEN and a short password as a validation error")
    void registerErrors() throws Exception {
        given(this.userService.register(any(), any(), any())).willThrow(new ModuleException.Conflict("exists", "EMAIL_TAKEN"));

        this.mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"a@example.com\",\"password\":\"correct horse\",\"displayName\":\"A\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
        this.mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"a@example.com\",\"password\":\"short\",\"displayName\":\"A\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors[0].field").value("password"));
    }

    @Test
    @DisplayName("GET /api/v1/me returns the signed-in user with permissions")
    void me() throws Exception {
        given(this.userService.get("u1")).willReturn(account("u1", Role.ADMIN));

        this.mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
        this.mvc.perform(get("/api/v1/me").with(jwt().jwt((t) -> t.subject("u1"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.permissions[0]").value("settings:manage"));
    }

    @Test
    @DisplayName("GET /api/v1/me/export returns the account and saved addresses")
    void export() throws Exception {
        // given
        given(this.userService.get("u1")).willReturn(account("u1", Role.CUSTOMER));
        given(this.addressBook.list("u1")).willReturn(List.of(new SavedAddress("a1", "HOME", null, 31.5, 74.3,
                "12 Main Boulevard", "Blue gate", true, java.time.Instant.ofEpochSecond(1_800_000_000L), 1)));

        // when / then
        this.mvc.perform(get("/api/v1/me/export")).andExpect(status().isUnauthorized());
        this.mvc.perform(get("/api/v1/me/export").with(jwt().jwt((t) -> t.subject("u1"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.account.id").value("u1"))
            .andExpect(jsonPath("$.account.roles[0]").value("CUSTOMER"))
            .andExpect(jsonPath("$.addresses[0].addressLine").value("12 Main Boulevard"));
    }

    @Test
    @DisplayName("POST /api/v1/me/deletion schedules the deletion and answers its date")
    void deletion() throws Exception {
        // given
        given(this.accountDeletion.request("u1")).willReturn(java.time.Instant.ofEpochSecond(1_800_000_000L));
        given(this.accountDeletion.grace()).willReturn(java.time.Duration.ofDays(14));
        given(this.accountDeletion.request("staff"))
            .willThrow(new ModuleException.Conflict("staff", "ACCOUNT_HAS_STAFF_ROLES"));

        // when / then
        this.mvc.perform(post("/api/v1/me/deletion").with(jwt().jwt((t) -> t.subject("u1"))))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.deleteAfter").value(1_800_000_000L))
            .andExpect(jsonPath("$.graceDays").value(14));
        this.mvc.perform(get("/api/v1/me/deletion").with(jwt().jwt((t) -> t.subject("u1"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.graceDays").value(14));
        this.mvc.perform(post("/api/v1/me/deletion").with(jwt().jwt((t) -> t.subject("staff"))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ACCOUNT_HAS_STAFF_ROLES"));
    }

    @Test
    @DisplayName("POST /api/v1/me/revoke-tokens logs the user out everywhere")
    void revoke() throws Exception {
        this.mvc.perform(post("/api/v1/me/revoke-tokens").with(jwt().jwt((t) -> t.subject("u1"))))
            .andExpect(status().isNoContent());

        verify(this.userService).revokeTokens("u1", "LOGOUT_EVERYWHERE");
    }

    @Test
    @DisplayName("back-office user management needs identity permissions")
    void users() throws Exception {
        given(this.userDirectory.search(null, null, null, null, 50))
            .willReturn(new UserDirectory.UserPage(List.of(account("u1", Role.ADMIN)), "u1"));
        given(this.userService.create(new NewUser("s@example.com", "correct horse", "S", Set.of(Role.SUPPORT)), "u1"))
            .willReturn(account("u2", Role.SUPPORT));
        String body = "{\"email\":\"s@example.com\",\"password\":\"correct horse\",\"displayName\":\"S\",\"roles\":[\"SUPPORT\"]}";

        this.mvc.perform(get("/api/v1/users").with(jwt())).andExpect(status().isForbidden());
        this.mvc.perform(get("/api/v1/users").with(jwt().authorities(List.of(new SimpleGrantedAuthority("identity:users:read")))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].id").value("u1"))
            .andExpect(jsonPath("$.nextCursor").value("u1"));
        this.mvc.perform(post("/api/v1/users").with(jwt().jwt((t) -> t.subject("u1"))
            .authorities(List.of(new SimpleGrantedAuthority("identity:users:manage"))))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.roles[0]").value("SUPPORT"));
    }

    @Test
    @DisplayName("the Users page searches every account by name, role and status, and blocks with a reason")
    void directory() throws Exception {
        given(this.userDirectory.search("ali", Role.CUSTOMER, UserStatus.ACTIVE, "u9", 20))
            .willReturn(new UserDirectory.UserPage(List.of(account("u3", Role.CUSTOMER)), null));
        given(this.userDirectory.block("u3", "Fraudulent orders", "admin")).willReturn(account("u3", Role.CUSTOMER));
        given(this.userDirectory.unblock("u3")).willReturn(account("u3", Role.CUSTOMER));
        var reader = jwt().authorities(List.of(new SimpleGrantedAuthority("identity:users:read")));
        var manager = jwt().jwt((t) -> t.subject("admin"))
            .authorities(List.of(new SimpleGrantedAuthority("identity:users:manage")));

        this.mvc.perform(get("/api/v1/users?q=ali&role=CUSTOMER&status=ACTIVE&after=u9&limit=20").with(reader))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].id").value("u3"));
        this.mvc.perform(post("/api/v1/users/u3/block").with(reader).contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"Fraudulent orders\"}")).andExpect(status().isForbidden());
        this.mvc.perform(post("/api/v1/users/u3/block").with(manager).contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"\"}")).andExpect(status().isBadRequest());
        this.mvc.perform(post("/api/v1/users/u3/block").with(manager).contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"Fraudulent orders\"}")).andExpect(status().isOk());
        this.mvc.perform(post("/api/v1/users/u3/unblock").with(manager)).andExpect(status().isOk());
        verify(this.userDirectory).block("u3", "Fraudulent orders", "admin");
    }

    @Test
    @DisplayName("POST /api/v1/auth/login: a browser gets the refresh token only as a locked-down cookie")
    void loginWeb() throws Exception {
        given(this.sessions.signInWithPassword("a@example.com", "correct horse", Client.WEB)).willReturn(session(Client.WEB));

        this.mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"client\":\"web\",\"email\":\"a@example.com\",\"password\":\"correct horse\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").value("access"))
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.expiresIn").value(600))
            .andExpect(jsonPath("$.refreshToken").doesNotExist())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(cookie().value("fs_refresh", "refresh"))
            .andExpect(cookie().httpOnly("fs_refresh", true))
            .andExpect(cookie().secure("fs_refresh", true))
            .andExpect(cookie().sameSite("fs_refresh", "Strict"))
            .andExpect(cookie().path("fs_refresh", "/api/v1/auth/refresh"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/login: a mobile app gets the refresh token in the body, with a code too")
    void loginMobile() throws Exception {
        given(this.sessions.signInWithCode("a@example.com", "123456", Client.MOBILE)).willReturn(session(Client.MOBILE));

        this.mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"client\":\"mobile\",\"email\":\"a@example.com\",\"code\":\"123456\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.refreshToken").value("refresh"))
            .andExpect(cookie().doesNotExist("fs_refresh"));

        this.mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"client\":\"tv\",\"email\":\"a@example.com\",\"code\":\"123456\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("AUTH_CLIENT_INVALID"));
        this.mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"client\":\"web\",\"email\":\"a@example.com\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("LOGIN_INVALID"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/refresh reads the cookie or the body, never both, and clears a dead cookie")
    void refresh() throws Exception {
        given(this.sessions.refresh("from-cookie", Client.WEB)).willReturn(session(Client.WEB));
        given(this.sessions.refresh("from-body", Client.MOBILE)).willReturn(session(Client.MOBILE));
        given(this.sessions.refresh("dead", Client.WEB))
            .willThrow(new ModuleException.Unauthorized("ended", "REFRESH_REUSED"));

        this.mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{}")
            .cookie(new jakarta.servlet.http.Cookie("fs_refresh", "from-cookie")))
            .andExpect(status().isOk())
            .andExpect(cookie().value("fs_refresh", "refresh"))
            .andExpect(jsonPath("$.refreshToken").doesNotExist());
        this.mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
            .content("{\"refreshToken\":\"from-body\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.refreshToken").value("refresh"));
        this.mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
            .content("{\"refreshToken\":\"from-body\"}").cookie(new jakarta.servlet.http.Cookie("fs_refresh", "from-cookie")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("REFRESH_TWO_CHANNELS"));
        this.mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{}")
            .cookie(new jakarta.servlet.http.Cookie("fs_refresh", "dead")))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("REFRESH_REUSED"))
            .andExpect(cookie().maxAge("fs_refresh", 0));
        // a form post from another site cannot send JSON
        this.mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .cookie(new jakarta.servlet.http.Cookie("fs_refresh", "from-cookie")))
            .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    @DisplayName("POST /api/v1/auth/logout ends the token's sign-in and clears the cookie")
    void logout() throws Exception {
        this.mvc.perform(post("/api/v1/auth/logout")).andExpect(status().isUnauthorized());
        this.mvc.perform(post("/api/v1/auth/logout").with(jwt().jwt((t) -> t.subject("u1").claim("sid", "family-1"))))
            .andExpect(status().isNoContent())
            .andExpect(cookie().maxAge("fs_refresh", 0));

        verify(this.sessions).end("u1", "family-1");
    }

    @Test
    @DisplayName("POST /api/v1/auth/code emails a code for sign-in or reset, and nothing else")
    void code() throws Exception {
        this.mvc.perform(post("/api/v1/auth/code").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\" a@example.com \",\"purpose\":\"login\"}"))
            .andExpect(status().isAccepted());
        verify(this.codes).issue("a@example.com", "LOGIN");

        this.mvc.perform(post("/api/v1/auth/code").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"a@example.com\",\"purpose\":\"other\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/auth/reset sets a new password and signs in")
    void reset() throws Exception {
        given(this.sessions.resetPassword("a@example.com", "123456", "a new long password", Client.WEB))
            .willReturn(session(Client.WEB));

        this.mvc.perform(post("/api/v1/auth/reset").contentType(MediaType.APPLICATION_JSON)
            .content("{\"client\":\"web\",\"email\":\"a@example.com\",\"code\":\"123456\",\"newPassword\":\"a new long password\"}"))
            .andExpect(status().isOk())
            .andExpect(cookie().exists("fs_refresh"));
        verifyNoInteractions(this.codes);
    }

    private static Session session(Client client) {
        return new Session("access", 600, "refresh", Instant.now().plus(java.time.Duration.ofDays(30)), client, "u1");
    }

    private static UserAccount account(String id, Role role) {
        return new UserAccount(id, id + "@example.com", "Name", Set.of(role),
                (role == Role.ADMIN) ? Set.of("settings:manage") : Set.of(), UserStatus.ACTIVE, Instant.ofEpochSecond(1_800_000_000L),
                Set.of(), Set.of());
    }
}
