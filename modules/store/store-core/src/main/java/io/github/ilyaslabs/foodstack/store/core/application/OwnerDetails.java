package io.github.ilyaslabs.foodstack.store.core.application;

/**
 * Someone with owner access.
 *
 * @param userId account id
 * @param email login email
 * @param displayName name
 */
public record OwnerDetails(String userId, String email, String displayName) {
}
