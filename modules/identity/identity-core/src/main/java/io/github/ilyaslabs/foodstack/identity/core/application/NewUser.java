package io.github.ilyaslabs.foodstack.identity.core.application;

import java.util.Set;

import io.github.ilyaslabs.foodstack.identity.api.Role;

/**
 * A user to create.
 *
 * @param email login email
 * @param password at least 10 characters
 * @param displayName 1 to 80 characters
 * @param roles roles to grant
 */
public record NewUser(String email, String password, String displayName, Set<Role> roles) {

    @Override
    public String toString() {
        return "NewUser[displayName=" + this.displayName + ", roles=" + this.roles + "]";
    }
}
