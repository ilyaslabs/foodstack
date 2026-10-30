package io.github.ilyaslabs.foodstack.identity.api;

/**
 * Roles carried in the {@code roles} claim. Permissions ({@code perms} claim) are derived from
 * them by the identity module. Store and rider roles arrive with their modules.
 */
public enum Role {

    /** Operator administrator: everything. */
    ADMIN,

    /** Operator finance staff: payouts, ledger, reports. */
    FINANCE,

    /** Operator support staff: orders and users. */
    SUPPORT,

    /** A customer of the marketplace. */
    CUSTOMER
}
