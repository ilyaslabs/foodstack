package io.github.ilyaslabs.foodstack.identity.api.events;

import java.util.Set;

import io.github.ilyaslabs.foodstack.identity.api.Role;
import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;

/**
 * A user account was created. Topic {@code foodstack.identity.events}, type
 * {@code foodstack.identity.UserRegistered.v1}. Carries no personal data; consumers ask
 * {@code IdentityApi} when they need it.
 *
 * @param userId the new user
 * @param roles granted roles
 * @param createdBy user who created it, or the user themself for sign-ups
 */
public record UserRegistered(String userId, Set<Role> roles, String createdBy) implements ModuleEvent {

    @Override
    public String subject() {
        return this.userId;
    }
}
