package io.github.ilyaslabs.foodstack.identity.web.dto;

import java.util.Set;

import io.github.ilyaslabs.foodstack.identity.api.Role;
import io.github.ilyaslabs.foodstack.identity.api.UserStatus;

/**
 * A user in the public API.
 *
 * @param id user id
 * @param email login email
 * @param displayName name
 * @param roles granted roles
 * @param permissions permissions derived from the roles (the apps show or hide areas with them)
 * @param status account state
 * @param createdAt UTC epoch seconds
 */
public record UserResponse(String id, String email, String displayName, Set<Role> roles, Set<String> permissions,
        UserStatus status, long createdAt) {
}
