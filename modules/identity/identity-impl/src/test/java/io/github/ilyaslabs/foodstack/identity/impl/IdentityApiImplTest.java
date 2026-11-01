package io.github.ilyaslabs.foodstack.identity.impl;

import java.time.Instant;
import java.util.Set;

import io.github.ilyaslabs.foodstack.identity.api.Role;
import io.github.ilyaslabs.foodstack.identity.api.UserStatus;
import io.github.ilyaslabs.foodstack.identity.api.UserView;
import io.github.ilyaslabs.foodstack.identity.core.application.UserAccount;
import io.github.ilyaslabs.foodstack.identity.core.application.UserService;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class IdentityApiImplTest {

    private final UserService service = mock(UserService.class);

    private final IdentityApiImpl api = new IdentityApiImpl(this.service);

    @org.junit.jupiter.api.AfterEach
    void signOut() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    /** Signs in a caller with these permissions, as the forwarded token would. */
    private static void callerWith(String subject, String... permissions) {
        org.springframework.security.oauth2.jwt.Jwt jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("t")
            .header("alg", "ES256")
            .subject(subject)
            .claim("perms", java.util.List.of(permissions))
            .build();
        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .setAuthentication(new io.github.ilyaslabs.foodstack.platform.security.FoodstackJwtAuthenticationConverter().convert(jwt));
    }

    @Test
    @DisplayName("maps a user account to the API record")
    void getUser() {
        callerWith("staff", "store:manage");
        given(this.service.get("u1")).willReturn(new UserAccount("u1", "a@example.com", "A", Set.of(Role.ADMIN),
                Set.of("settings:manage"), UserStatus.ACTIVE, Instant.ofEpochSecond(1_800_000_000L), Set.of(), Set.of()));

        assertThat(this.api.getUser("u1"))
            .isEqualTo(new UserView("u1", "a@example.com", "A", Set.of(Role.ADMIN), UserStatus.ACTIVE, 1_800_000_000L, Set.of(),
                    Set.of()));
    }

    @Test
    @DisplayName("passes USER_NOT_FOUND through")
    void notFound() {
        callerWith("foodstack-service", "module:service");
        given(this.service.get("x")).willThrow(new ModuleException.NotFound("User x not found", "USER_NOT_FOUND"));

        assertThatThrownBy(() -> this.api.getUser("x")).isInstanceOf(ModuleException.NotFound.class);
    }

    @Test
    @DisplayName("a user reads only itself; store access needs store:manage; no token means 401 (TDD 9.1)")
    void permissions() {
        given(this.service.get("u1")).willReturn(new UserAccount("u1", "a@example.com", "A", Set.of(Role.CUSTOMER),
                Set.of(), UserStatus.ACTIVE, Instant.ofEpochSecond(1_800_000_000L), Set.of(), Set.of()));

        assertThatThrownBy(() -> this.api.getUser("u1")).isInstanceOf(ModuleException.Unauthorized.class);
        callerWith("u1");
        assertThat(this.api.getUser("u1").id()).isEqualTo("u1");
        callerWith("u2");
        assertThatThrownBy(() -> this.api.getUser("u1")).isInstanceOf(ModuleException.Forbidden.class);
        callerWith("owner", "store:portal");
        assertThatThrownBy(() -> this.api.revokeStoreAccess("u1", "b1", "s1")).isInstanceOf(ModuleException.Forbidden.class);
    }
}
