package io.github.ilyaslabs.foodstack.identity.api;

/**
 * Brand owner access to give to a person (see {@link IdentityApi#grantBrandAccess}).
 *
 * @param email login email of the person
 * @param displayName name for a new account; ignored for an existing one
 * @param initialPassword password of a new account (10 to 128 characters); ignored for an existing one
 * @param brandId brand to add to the {@code brandIds} claim
 * @param grantedBy user who gave the access
 */
public record BrandAccessGrant(String email, String displayName, String initialPassword, String brandId, String grantedBy) {

    @Override
    public String toString() {
        return "BrandAccessGrant[brandId=" + this.brandId + "]";
    }
}
