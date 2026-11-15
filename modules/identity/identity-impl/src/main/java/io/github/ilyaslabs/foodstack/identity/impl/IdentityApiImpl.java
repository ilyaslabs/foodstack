package io.github.ilyaslabs.foodstack.identity.impl;

import io.github.ilyaslabs.evl.EVL;
import io.github.ilyaslabs.evl.Flow;
import io.github.ilyaslabs.foodstack.identity.api.BrandAccessGrant;
import io.github.ilyaslabs.foodstack.identity.api.IdentityApi;
import io.github.ilyaslabs.foodstack.identity.api.RiderAccessGrant;
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
    @Flow(value = "identity-api-get-user", label = "A user for other modules", description = "Self, or a service with users:read or store:manage")
    @EVL.Endpoint(method = "API", path = "IdentityApi.getUser", caller = "Order, review, notification modules")
    public UserView getUser(String userId) {
        ModuleAccess.requireServiceSelfOr(userId, Permissions.USERS_READ, Permissions.STORE_MANAGE);
        return toView(this.userService.get(userId));
    }

    @Override
    @Flow(value = "identity-api-grant-store-access", label = "Give a branch owner access", description = "Creates the account when the email is new")
    @EVL.Endpoint(method = "API", path = "IdentityApi.grantStoreAccess", caller = "Store module")
    public UserView grantStoreAccess(StoreAccessGrant access) {
        ModuleAccess.requireServiceOr(Permissions.STORE_MANAGE);
        return toView(this.userService.grantStoreAccess(access.email(), access.displayName(), access.initialPassword(),
                access.storeId(), access.grantedBy()));
    }

    @Override
    @Flow(value = "identity-api-grant-brand-access", label = "Give a brand owner access", description = "Creates the account when the email is new")
    @EVL.Endpoint(method = "API", path = "IdentityApi.grantBrandAccess", caller = "Store module")
    public UserView grantBrandAccess(BrandAccessGrant access) {
        ModuleAccess.requireServiceOr(Permissions.STORE_MANAGE);
        return toView(this.userService.grantBrandAccess(access.email(), access.displayName(), access.initialPassword(),
                access.brandId(), access.grantedBy()));
    }

    @Override
    @Flow(value = "identity-api-revoke-brand-access", label = "Take brand owner access away", description = "Revokes the user's tokens")
    @EVL.Endpoint(method = "API", path = "IdentityApi.revokeBrandAccess", caller = "Store module")
    public void revokeBrandAccess(String userId, String brandId) {
        ModuleAccess.requireServiceOr(Permissions.STORE_MANAGE);
        this.userService.revokeBrandAccess(userId, brandId);
    }

    @Override
    @Flow(value = "identity-api-grant-rider-access", label = "Give rider access", description = "Operator or branch rider")
    @EVL.Endpoint(method = "API", path = "IdentityApi.grantRiderAccess", caller = "Rider module")
    public UserView grantRiderAccess(RiderAccessGrant access) {
        // the rider module checks a store's right to its own riders, then calls as the platform
        ModuleAccess.requireServiceOr(Permissions.RIDER_MANAGE);
        boolean storeRider = access.storeId() != null && !access.storeId().isBlank();
        return toView(this.userService.grantRiderAccess(access.email(), access.displayName(), access.initialPassword(),
                storeRider, access.grantedBy()));
    }

    @Override
    @Flow(value = "identity-api-user-ids-with-roles", label = "Users with roles", description = "Active users with any of the roles")
    @EVL.Endpoint(method = "API", path = "IdentityApi.userIdsWithRoles", caller = "Notification module")
    public java.util.List<String> userIdsWithRoles(java.util.Set<io.github.ilyaslabs.foodstack.identity.api.Role> roles) {
        ModuleAccess.requireServiceOr();
        return this.userService.activeUserIdsWithRoles(roles);
    }

    @Override
    @Flow(value = "identity-api-revoke-rider-access", label = "Take rider access away", description = "Revokes the user's tokens")
    @EVL.Endpoint(method = "API", path = "IdentityApi.revokeRiderAccess", caller = "Rider module")
    public void revokeRiderAccess(String userId) {
        ModuleAccess.requireServiceOr(Permissions.RIDER_MANAGE);
        this.userService.revokeRiderAccess(userId);
    }

    @Override
    @Flow(value = "identity-api-revoke-store-access", label = "Take branch owner access away", description = "Revokes the user's tokens")
    @EVL.Endpoint(method = "API", path = "IdentityApi.revokeStoreAccess", caller = "Store module")
    public void revokeStoreAccess(String userId, String storeId) {
        ModuleAccess.requireServiceOr(Permissions.STORE_MANAGE);
        this.userService.revokeStoreAccess(userId, storeId);
    }

    private static UserView toView(UserAccount user) {
        return new UserView(user.id(), user.email(), user.displayName(), user.roles(), user.status(),
                user.createdAt().getEpochSecond(), user.brandIds(), user.storeIds());
    }
}
