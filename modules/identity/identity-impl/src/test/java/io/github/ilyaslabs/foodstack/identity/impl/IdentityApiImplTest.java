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

    @Test
    @DisplayName("maps a user account to the API record")
    void getUser() {
        given(this.service.get("u1")).willReturn(new UserAccount("u1", "a@example.com", "A", Set.of(Role.ADMIN),
                Set.of("settings:manage"), UserStatus.ACTIVE, Instant.ofEpochSecond(1_800_000_000L)));

        assertThat(this.api.getUser("u1"))
            .isEqualTo(new UserView("u1", "a@example.com", "A", Set.of(Role.ADMIN), UserStatus.ACTIVE, 1_800_000_000L));
    }

    @Test
    @DisplayName("passes USER_NOT_FOUND through")
    void notFound() {
        given(this.service.get("x")).willThrow(new ModuleException.NotFound("User x not found", "USER_NOT_FOUND"));

        assertThatThrownBy(() -> this.api.getUser("x")).isInstanceOf(ModuleException.NotFound.class);
    }
}
