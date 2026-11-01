package io.github.ilyaslabs.foodstack.identity.impl;

import io.github.ilyaslabs.foodstack.identity.api.IdentityApi;
import io.github.ilyaslabs.foodstack.identity.api.StoreAccessGrant;
import io.github.ilyaslabs.foodstack.identity.api.UserView;
import io.github.ilyaslabs.foodstack.identity.core.application.UserAccount;
import io.github.ilyaslabs.foodstack.identity.core.application.UserService;

import io.github.ilyaslabs.foodstack.platform.security.ModuleAccess;
import io.github.ilyaslabs.foodstack.identity.core.domain.Permissions;

import org.springframework.stereotype.Component;

/**
 * {@link IdentityApi} for other modules: delegates to the core service and maps the result. A user
 * may be read by the platform, by itself, or by staff who read users or manage stores (to list
 * owners); store access is granted and revoked only for operator staff who manage stores (TDD 9.1).
 */
@Component
class IdentityApiImpl implements IdentityApi {

    private final UserService userService;

    IdentityApiImpl(UserService userService) {
        this.userService = userService;
    }

    @Override
    public UserView getUser(String userId) {
        ModuleAccess.requireServiceSelfOr(userId, Permissions.USERS_READ, Permissions.STORE_MANAGE);
        return toView(this.userService.get(userId));
    }

    @Override
    public UserView grantStoreAccess(StoreAccessGrant access) {
        ModuleAccess.requireServiceOr(Permissions.STORE_MANAGE);
        return toView(this.userService.grantStoreAccess(access.email(), access.displayName(), access.initialPassword(),
                access.brandId(), access.storeId(), access.grantedBy()));
    }

    @Override
    public void revokeStoreAccess(String userId, String brandId, String storeId) {
        ModuleAccess.requireServiceOr(Permissions.STORE_MANAGE);
        this.userService.revokeStoreAccess(userId, brandId, storeId);
    }

    private static UserView toView(UserAccount user) {
        return new UserView(user.id(), user.email(), user.displayName(), user.roles(), user.status(),
                user.createdAt().getEpochSecond(), user.brandIds(), user.storeIds());
    }
}
