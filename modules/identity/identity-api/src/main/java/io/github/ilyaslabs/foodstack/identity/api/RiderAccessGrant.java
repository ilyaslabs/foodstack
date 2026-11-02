package io.github.ilyaslabs.foodstack.identity.api;

/**
 * Rider access to give to a person (see {@link IdentityApi#grantRiderAccess}).
 *
 * @param email login email of the person
 * @param displayName name for a new account; ignored for an existing one
 * @param initialPassword password of a new account (10 to 128 characters); ignored for an existing one
 * @param storeId the branch of a store rider, or {@code null} for an operator rider
 * @param grantedBy user who gave the access
 */
public record RiderAccessGrant(String email, String displayName, String initialPassword, String storeId, String grantedBy) {

    @Override
    public String toString() {
        return "RiderAccessGrant[storeId=" + this.storeId + "]";
    }
}
