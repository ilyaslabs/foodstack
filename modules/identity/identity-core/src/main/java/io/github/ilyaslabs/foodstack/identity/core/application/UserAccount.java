package io.github.ilyaslabs.foodstack.identity.core.application;

import java.time.Instant;
import java.util.Set;

import io.github.ilyaslabs.foodstack.identity.api.Role;
import io.github.ilyaslabs.foodstack.identity.api.UserStatus;

/**
 * A user as the application services return it, email decrypted.
 *
 * @param id user id
 * @param email login email
 * @param displayName name shown to others
 * @param roles granted roles
 * @param permissions permissions derived from the roles
 * @param status account state
 * @param createdAt creation time
 */
public record UserAccount(String id, String email, String displayName, Set<Role> roles, Set<String> permissions,
        UserStatus status, Instant createdAt) {
}
