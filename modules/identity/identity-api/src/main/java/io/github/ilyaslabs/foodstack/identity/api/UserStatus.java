package io.github.ilyaslabs.foodstack.identity.api;

public enum UserStatus {

    ACTIVE,

    /** Cannot log in; existing tokens are revoked. */
    BLOCKED,

    /**
     * Deleted at the customer's request (PRD ID-7): personal details are erased, the id stays on
     * past orders. Cannot log in again.
     */
    DELETED
}
