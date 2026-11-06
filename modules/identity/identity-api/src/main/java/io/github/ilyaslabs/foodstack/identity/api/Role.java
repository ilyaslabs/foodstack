package io.github.ilyaslabs.foodstack.identity.api;

/**
 * Roles carried in the {@code roles} claim. Permissions ({@code perms} claim) are derived from
 * them by the identity module.
 */
public enum Role {

    /** Operator administrator: everything. */
    ADMIN,

    /** Operator finance staff: payouts, ledger, reports. */
    FINANCE,

    /** Operator support staff: orders and users. */
    SUPPORT,

    /** A customer of the marketplace. */
    CUSTOMER,

    /**
     * Owner of one or more brands ({@code brandIds} claim, PRD BB-5): the brand, its menu and prices,
     * and every branch of it.
     */
    BRAND_OWNER,

    /**
     * Owner or manager of one or more branches ({@code storeIds} claim), for example a franchisee
     * (PRD BB-5, BB-6): hours, the temporary-close switch, sold-out marks, orders, riders, delivery
     * and the branch's payouts. Never the brand menu or prices.
     */
    STORE_OWNER,

    /** Staff of a branch: orders and sold-out toggles only ({@code storeIds} claim). */
    STORE_STAFF,

    /** An operator rider (PRD DR-1): receives offers for operator-delivery orders. */
    RIDER,

    /**
     * A store's own rider (PRD DR-1): gets jobs of that store only. The store is kept by the rider
     * module, not in the {@code storeIds} claim, so a rider never gets store-owner access.
     */
    STORE_RIDER
}
