package io.github.ilyaslabs.foodstack.identity.api;

/**
 * Owner access to give to a person (see {@link IdentityApi#grantStoreAccess}).
 *
 * @param email login email of the person
 * @param displayName name for a new account; ignored for an existing one
 * @param initialPassword password of a new account (10 to 128 characters); ignored for an existing one
 * @param storeId branch to add to the {@code storeIds} claim
 * @param grantedBy user who gave the access
 */
public record StoreAccessGrant(String email, String displayName, String initialPassword, String storeId, String grantedBy) {

    @Override
    public String toString() {
        return "StoreAccessGrant[storeId=" + this.storeId + "]";
    }
}
