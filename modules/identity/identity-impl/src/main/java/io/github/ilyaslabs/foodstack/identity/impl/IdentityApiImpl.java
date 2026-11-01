package io.github.ilyaslabs.foodstack.identity.impl;

import io.github.ilyaslabs.foodstack.identity.api.IdentityApi;
import io.github.ilyaslabs.foodstack.identity.api.StoreAccessGrant;
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
        return toView(this.userService.get(userId));
    }

    @Override
    public UserView grantStoreAccess(StoreAccessGrant access) {
        return toView(this.userService.grantStoreAccess(access.email(), access.displayName(), access.initialPassword(),
                access.brandId(), access.storeId(), access.grantedBy()));
    }

    @Override
    public void revokeStoreAccess(String userId, String brandId, String storeId) {
        this.userService.revokeStoreAccess(userId, brandId, storeId);
    }

    private static UserView toView(UserAccount user) {
        return new UserView(user.id(), user.email(), user.displayName(), user.roles(), user.status(),
                user.createdAt().getEpochSecond(), user.brandIds(), user.storeIds());
    }
}
