package io.github.ilyaslabs.foodstack.identity.core.domain;

import java.util.EnumSet;

import io.github.ilyaslabs.foodstack.identity.api.Role;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionsTest {

    @Test
    @DisplayName("gives administrators every permission, store owners the store area and customers only review photos")
    void byRole() {
        assertThat(Permissions.of(EnumSet.of(Role.ADMIN))).containsExactly("dispatch:manage", "geo:manage",
                "identity:audit:read", "identity:users:manage", "identity:users:read", "ledger:read", "ledger:settle",
                "media:upload", "order:manage", "payment:manage",
                "promotion:manage", "review:moderate", "rider:manage", "search:manage", "settings:manage", "store:manage",
                "store:payment-collection:manage");
        assertThat(Permissions.of(EnumSet.of(Role.STORE_OWNER))).containsExactly("ledger:store", "media:upload", "payment:account",
                "store:portal");
        assertThat(Permissions.of(EnumSet.of(Role.BRAND_OWNER))).containsExactly("ledger:store", "media:upload",
                "payment:account", "store:portal");
        assertThat(Permissions.of(EnumSet.of(Role.STORE_STAFF))).containsExactly("store:portal");
        assertThat(Permissions.of(EnumSet.of(Role.CUSTOMER))).containsExactly("media:upload:review");
        assertThat(Permissions.of(EnumSet.of(Role.FINANCE))).containsExactly("identity:users:read", "ledger:read", "ledger:settle",
                "payment:manage", "store:payment-collection:manage");
        assertThat(Permissions.of(EnumSet.of(Role.SUPPORT))).containsExactly("dispatch:manage", "identity:users:read",
                "order:manage", "review:moderate");
        assertThat(Permissions.of(EnumSet.of(Role.RIDER))).containsExactly("media:upload", "rider:portal");
        assertThat(Permissions.of(EnumSet.of(Role.STORE_RIDER))).containsExactly("media:upload", "rider:portal");
    }

    @Test
    @DisplayName("treats every role but customer as back office")
    void backOffice() {
        assertThat(Permissions.isBackOffice(EnumSet.of(Role.CUSTOMER))).isFalse();
        assertThat(Permissions.isBackOffice(EnumSet.of(Role.SUPPORT))).isTrue();
    }
}
