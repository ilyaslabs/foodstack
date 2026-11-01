package io.github.ilyaslabs.foodstack.identity.api;

import io.github.ilyaslabs.moduleswitch.Param;

/**
 * What other modules may ask the identity module. Tokens are never checked here: every module
 * validates them itself (TDD 9.1). Errors: {@code USER_NOT_FOUND} (404).
 */
public interface IdentityApi {

    /** A user by id, for example to address a notification. */
    UserView getUser(@Param("userId") String userId);

    /**
     * Gives a person owner access to a brand and its branch: an existing account (found by email)
     * gets the {@code STORE_OWNER} role and the ids added to its token claims; otherwise an account is
     * created with {@code initialPassword}. Errors: {@code USER_INVALID} (400) when a new account's
     * details are invalid or the password is missing.
     */
    UserView grantStoreAccess(@Param("access") StoreAccessGrant access);

    /** Takes a brand and branch out of a user's claims and revokes their tokens. Unknown users are ignored. */
    void revokeStoreAccess(@Param("userId") String userId, @Param("brandId") String brandId,
            @Param("storeId") String storeId);
}
