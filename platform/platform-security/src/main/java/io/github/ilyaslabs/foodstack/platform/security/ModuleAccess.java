package io.github.ilyaslabs.foodstack.platform.security;

import io.github.ilyaslabs.moduleswitch.ModuleException;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Permission checks inside a module's {@code -impl} (TDD 9.1): a module API answers only what the
 * caller could ask for itself. The caller is the user whose token came with the request (forwarded
 * by Module-Switch when the module runs elsewhere), or the platform itself for background work,
 * which runs with a service token through {@link ServiceCalls}.
 */
public final class ModuleAccess {

    /** Held only by service tokens: background work of the platform itself (event handlers, rebuilds, seeding). */
    public static final String SERVICE = "module:service";

    /** The {@code sub} of service tokens, also the OAuth client id. */
    public static final String SERVICE_CLIENT = "foodstack-service";

    private ModuleAccess() {
    }

    /** Whether the current call runs with a service token. */
    public static boolean isService() {
        return CurrentUser.hasAuthority(SERVICE);
    }

    /** 401 without a token. */
    public static void requireAuthenticated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof org.springframework.security.authentication.AnonymousAuthenticationToken) {
            throw new ModuleException.Unauthorized("Authentication required", "UNAUTHENTICATED");
        }
    }

    /**
     * Passes for a service token or a token holding any of {@code permissions}; 401 without a token,
     * 403 {@code MODULE_ACCESS_DENIED} otherwise.
     */
    public static void requireServiceOr(String... permissions) {
        requireAuthenticated();
        if (isService()) {
            return;
        }
        for (String permission : permissions) {
            if (CurrentUser.hasAuthority(permission)) {
                return;
            }
        }
        throw new ModuleException.Forbidden("You may not do this", "MODULE_ACCESS_DENIED");
    }

    /** Passes for a service token, the user {@code userId} itself, or a holder of any of {@code permissions}. */
    public static void requireServiceSelfOr(String userId, String... permissions) {
        requireAuthenticated();
        if (isService() || CurrentUser.id().equals(userId)) {
            return;
        }
        requireServiceOr(permissions);
    }
}
