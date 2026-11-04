package io.github.ilyaslabs.foodstack.platform.security;

/** Claims FoodStack puts into access tokens (TDD 9.1). */
public final class TokenClaims {

    /** Granted permissions, such as {@code settings:manage}. */
    /** The sign-in (refresh token family) a user access token belongs to. */
    public static final String SESSION_ID = "sid";

    public static final String PERMISSIONS = "perms";

    /** Roles, such as {@code ADMIN}. */
    public static final String ROLES = "roles";

    /** The user's token version; tokens older than the revoked version are rejected. */
    public static final String TOKEN_VERSION = "tv";

    /** Brands the user owns (brand-level actions such as editing the menu). */
    public static final String BRAND_IDS = "brandIds";

    /** Branches the user manages or works at. */
    public static final String STORE_IDS = "storeIds";

    private TokenClaims() {
    }
}
