package io.github.ilyaslabs.foodstack.identity.api;

import java.util.Set;

/**
 * A user.
 *
 * @param id user id, the {@code sub} of their tokens
 * @param email login email
 * @param displayName name shown to others
 * @param roles granted roles
 * @param status account state
 * @param createdAt UTC epoch seconds
 * @param brandIds brands the user owns
 * @param storeIds branches the user manages
 */
public record UserView(String id, String email, String displayName, Set<Role> roles, UserStatus status, long createdAt,
        Set<String> brandIds, Set<String> storeIds) {
}
