package io.github.ilyaslabs.foodstack.identity.core.domain;

import java.util.EnumSet;

import io.github.ilyaslabs.foodstack.identity.api.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionsTest {

    @Test
    @DisplayName("gives administrators every permission, store owners the store area and customers none")
    void byRole() {
        assertThat(Permissions.of(EnumSet.of(Role.ADMIN))).containsExactly("geo:manage", "identity:users:manage",
                "identity:users:read", "media:upload", "order:manage", "payment:manage", "promotion:manage", "search:manage",
                "settings:manage", "store:manage");
        assertThat(Permissions.of(EnumSet.of(Role.STORE_OWNER))).containsExactly("media:upload", "store:portal");
        assertThat(Permissions.of(EnumSet.of(Role.STORE_STAFF))).containsExactly("store:portal");
        assertThat(Permissions.of(EnumSet.of(Role.CUSTOMER))).isEmpty();
        assertThat(Permissions.of(EnumSet.of(Role.FINANCE))).containsExactly("identity:users:read", "payment:manage");
        assertThat(Permissions.of(EnumSet.of(Role.SUPPORT))).containsExactly("identity:users:read", "order:manage");
    }

    @Test
    @DisplayName("treats every role but customer as back office")
    void backOffice() {
        assertThat(Permissions.isBackOffice(EnumSet.of(Role.CUSTOMER))).isFalse();
        assertThat(Permissions.isBackOffice(EnumSet.of(Role.SUPPORT))).isTrue();
    }
}
