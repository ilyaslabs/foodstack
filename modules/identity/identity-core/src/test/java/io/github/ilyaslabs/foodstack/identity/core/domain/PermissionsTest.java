package io.github.ilyaslabs.foodstack.identity.core.domain;

import java.util.EnumSet;

import io.github.ilyaslabs.foodstack.identity.api.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionsTest {

    @Test
    @DisplayName("gives administrators every M0 permission and customers none")
    void byRole() {
        assertThat(Permissions.of(EnumSet.of(Role.ADMIN)))
            .containsExactly("identity:users:manage", "identity:users:read", "media:upload", "settings:manage");
        assertThat(Permissions.of(EnumSet.of(Role.CUSTOMER))).isEmpty();
        assertThat(Permissions.of(EnumSet.of(Role.FINANCE, Role.SUPPORT))).containsExactly("identity:users:read");
    }

    @Test
    @DisplayName("treats every role but customer as back office")
    void backOffice() {
        assertThat(Permissions.isBackOffice(EnumSet.of(Role.CUSTOMER))).isFalse();
        assertThat(Permissions.isBackOffice(EnumSet.of(Role.SUPPORT))).isTrue();
    }
}
