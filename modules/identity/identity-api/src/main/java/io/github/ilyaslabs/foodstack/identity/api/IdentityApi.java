package io.github.ilyaslabs.foodstack.identity.api;

import io.github.ilyaslabs.moduleswitch.Param;

/**
 * What other modules may ask the identity module. Tokens are never checked here: every module
 * validates them itself (TDD 9.1). Errors: {@code USER_NOT_FOUND} (404).
 */
public interface IdentityApi {

    /** A user by id, for example to address a notification. */
    UserView getUser(@Param("userId") String userId);
}
