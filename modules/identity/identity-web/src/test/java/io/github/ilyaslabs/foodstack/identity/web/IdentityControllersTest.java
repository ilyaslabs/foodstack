package io.github.ilyaslabs.foodstack.identity.web;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import io.github.ilyaslabs.foodstack.identity.api.Role;
import io.github.ilyaslabs.foodstack.identity.api.UserStatus;
import io.github.ilyaslabs.foodstack.identity.core.application.NewUser;
import io.github.ilyaslabs.foodstack.identity.core.application.UserAccount;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    JwtDecoder jwtDecoder;

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
    @DisplayName("POST /api/v1/me/revoke-tokens logs the user out everywhere")
    void revoke() throws Exception {
        this.mvc.perform(post("/api/v1/me/revoke-tokens").with(jwt().jwt((t) -> t.subject("u1"))))
            .andExpect(status().isNoContent());

        verify(this.userService).revokeTokens("u1", "LOGOUT_EVERYWHERE");
    }

    @Test
    @DisplayName("back-office user management needs identity permissions")
    void users() throws Exception {
        given(this.userService.listBackOffice(50)).willReturn(List.of(account("u1", Role.ADMIN)));
        given(this.userService.create(new NewUser("s@example.com", "correct horse", "S", Set.of(Role.SUPPORT)), "u1"))
            .willReturn(account("u2", Role.SUPPORT));
        String body = "{\"email\":\"s@example.com\",\"password\":\"correct horse\",\"displayName\":\"S\",\"roles\":[\"SUPPORT\"]}";

        this.mvc.perform(get("/api/v1/users").with(jwt())).andExpect(status().isForbidden());
        this.mvc.perform(get("/api/v1/users").with(jwt().authorities(List.of(new SimpleGrantedAuthority("identity:users:read")))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].id").value("u1"));
        this.mvc.perform(post("/api/v1/users").with(jwt().jwt((t) -> t.subject("u1"))
            .authorities(List.of(new SimpleGrantedAuthority("identity:users:manage"))))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.roles[0]").value("SUPPORT"));
    }

    private static UserAccount account(String id, Role role) {
        return new UserAccount(id, id + "@example.com", "Name", Set.of(role),
                (role == Role.ADMIN) ? Set.of("settings:manage") : Set.of(), UserStatus.ACTIVE, Instant.ofEpochSecond(1_800_000_000L));
    }
}
