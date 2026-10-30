package io.github.ilyaslabs.foodstack.identity.core.domain;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import io.github.ilyaslabs.foodstack.identity.api.Role;

/**
 * Permissions granted by each role; they travel in the {@code perms} claim so modules authorize
 * without a lookup (TDD 9.1). New modules add their permissions here.
 */
public final class Permissions {

    public static final String SETTINGS_MANAGE = "settings:manage";

    public static final String MEDIA_UPLOAD = "media:upload";

    public static final String USERS_READ = "identity:users:read";

    public static final String USERS_MANAGE = "identity:users:manage";

    private static final Map<Role, Set<String>> BY_ROLE = new EnumMap<>(Role.class);

    static {
        BY_ROLE.put(Role.ADMIN, Set.of(SETTINGS_MANAGE, MEDIA_UPLOAD, USERS_READ, USERS_MANAGE));
        BY_ROLE.put(Role.FINANCE, Set.of(USERS_READ));
        BY_ROLE.put(Role.SUPPORT, Set.of(USERS_READ));
        BY_ROLE.put(Role.CUSTOMER, Set.of());
    }

    private Permissions() {
    }

    /** Every permission of a set of roles, sorted. */
    public static Set<String> of(Collection<Role> roles) {
        Set<String> permissions = new TreeSet<>();
        for (Role role : roles) {
            permissions.addAll(BY_ROLE.getOrDefault(role, Set.of()));
        }
        return permissions;
    }

    /** Back-office roles are everything but {@link Role#CUSTOMER}. */
    public static boolean isBackOffice(Collection<Role> roles) {
        return roles.stream().anyMatch((role) -> role != Role.CUSTOMER);
    }
}
