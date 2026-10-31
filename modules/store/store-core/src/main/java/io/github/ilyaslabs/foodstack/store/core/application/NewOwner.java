package io.github.ilyaslabs.foodstack.store.core.application;

/**
 * A person to give owner access to a branch and its brand.
 *
 * @param email login email
 * @param displayName name for a new account
 * @param initialPassword password for a new account; ignored when the email already has one
 */
public record NewOwner(String email, String displayName, String initialPassword) {

    @Override
    public String toString() {
        return "NewOwner[displayName=" + this.displayName + "]";
    }
}
