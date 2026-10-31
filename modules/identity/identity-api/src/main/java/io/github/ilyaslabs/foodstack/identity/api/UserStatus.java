package io.github.ilyaslabs.foodstack.identity.api;

public enum UserStatus {

    ACTIVE,

    /** Cannot log in; existing tokens are revoked. */
    BLOCKED
}
