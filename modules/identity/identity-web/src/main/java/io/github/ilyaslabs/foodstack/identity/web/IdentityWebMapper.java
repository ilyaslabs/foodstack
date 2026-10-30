package io.github.ilyaslabs.foodstack.identity.web;

import io.github.ilyaslabs.foodstack.identity.core.application.UserAccount;
import io.github.ilyaslabs.foodstack.identity.web.dto.UserResponse;

final class IdentityWebMapper {

    private IdentityWebMapper() {
    }

    static UserResponse toResponse(UserAccount user) {
        return new UserResponse(user.id(), user.email(), user.displayName(), user.roles(), user.permissions(), user.status(),
                user.createdAt().getEpochSecond());
    }
}
