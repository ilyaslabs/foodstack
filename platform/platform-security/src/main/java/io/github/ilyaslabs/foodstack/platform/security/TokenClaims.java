package io.github.ilyaslabs.foodstack.platform.security;

/** Claims FoodStack puts into access tokens (TDD 9.1). */
public final class TokenClaims {

    /** Granted permissions, such as {@code settings:manage}. */
    public static final String PERMISSIONS = "perms";

    /** Roles, such as {@code ADMIN}. */
    public static final String ROLES = "roles";

    /** The user's token version; tokens older than the revoked version are rejected. */
    public static final String TOKEN_VERSION = "tv";

    private TokenClaims() {
    }
}
