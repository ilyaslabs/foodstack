package io.github.ilyaslabs.foodstack.identity.impl;

import io.github.ilyaslabs.foodstack.identity.api.IdentityApi;
import io.github.ilyaslabs.foodstack.identity.api.UserView;
import io.github.ilyaslabs.foodstack.identity.core.application.UserAccount;
import io.github.ilyaslabs.foodstack.identity.core.application.UserService;

import org.springframework.stereotype.Component;

/** {@link IdentityApi} for other modules: delegates to the core service and maps the result. */
@Component
class IdentityApiImpl implements IdentityApi {

    private final UserService userService;

    IdentityApiImpl(UserService userService) {
        this.userService = userService;
    }

    @Override
    public UserView getUser(String userId) {
        UserAccount user = this.userService.get(userId);
        return new UserView(user.id(), user.email(), user.displayName(), user.roles(), user.status(),
                user.createdAt().getEpochSecond());
    }
}
