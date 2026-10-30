package io.github.ilyaslabs.foodstack.identity.api.events;

import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;

/**
 * Every token of a user older than {@code minimumTokenVersion} is invalid (blocked account,
 * password change, role removed, logout everywhere). Topic {@code foodstack.identity.events},
 * type {@code foodstack.identity.UserTokensRevoked.v1}.
 *
 * @param userId the user
 * @param minimumTokenVersion lowest {@code tv} claim still accepted
 * @param reason why, for audit
 */
public record UserTokensRevoked(String userId, long minimumTokenVersion, String reason) implements ModuleEvent {

    @Override
    public String subject() {
        return this.userId;
    }
}
