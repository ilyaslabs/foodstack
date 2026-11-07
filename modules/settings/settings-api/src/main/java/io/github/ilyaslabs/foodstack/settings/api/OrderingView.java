package io.github.ilyaslabs.foodstack.settings.api;

/**
 * The operator's ordering rules (PRD CO-2, CO-5, and the accept time of section 3). Amounts are in
 * minor units of the deployment currency; rates are in basis points (100 = 1%).
 *
 * @param cashEnabled customers may pay cash on delivery or at pickup
 * @param cardEnabled customers may pay by card (also needs a configured gateway)
 * @param serviceFeeMinor fixed service fee per order
 * @param serviceFeeBasisPoints service fee as a share of the food subtotal, added to the fixed part
 * @param taxBasisPoints tax added on the food subtotal after discounts, 0 for none
 * @param tipsEnabled customers may add a tip for the rider at checkout
 * @param acceptTimeoutSeconds time a store has to accept a paid order before it is rejected and refunded
 * @param version increases with every change
 * @param priceApprovalRequired store owners' price changes wait for operator approval (PRD ST-6, BB-4)
 * @param directCollectionEnabled branches may be allowed to collect card payments themselves (PRD DP-1, off by default)
 * @param creditLimitMinor what a direct-collecting branch may owe before collection is suspended, unless it has its own
 *        limit (PRD DP-7)
 */
public record OrderingView(boolean cashEnabled, boolean cardEnabled, long serviceFeeMinor, int serviceFeeBasisPoints,
        int taxBasisPoints, boolean tipsEnabled, int acceptTimeoutSeconds, long version, boolean priceApprovalRequired,
        boolean directCollectionEnabled, long creditLimitMinor) {

    /** The default credit limit: 50,000 in a two-decimal currency. */
    public static final long DEFAULT_CREDIT_LIMIT_MINOR = 5_000_000L;

    /** Rules without direct collection. */
    public OrderingView(boolean cashEnabled, boolean cardEnabled, long serviceFeeMinor, int serviceFeeBasisPoints,
            int taxBasisPoints, boolean tipsEnabled, int acceptTimeoutSeconds, long version, boolean priceApprovalRequired) {
        this(cashEnabled, cardEnabled, serviceFeeMinor, serviceFeeBasisPoints, taxBasisPoints, tipsEnabled,
                acceptTimeoutSeconds, version, priceApprovalRequired, false, DEFAULT_CREDIT_LIMIT_MINOR);
    }

    /** Rules without price approval. */
    public OrderingView(boolean cashEnabled, boolean cardEnabled, long serviceFeeMinor, int serviceFeeBasisPoints,
            int taxBasisPoints, boolean tipsEnabled, int acceptTimeoutSeconds, long version) {
        this(cashEnabled, cardEnabled, serviceFeeMinor, serviceFeeBasisPoints, taxBasisPoints, tipsEnabled,
                acceptTimeoutSeconds, version, false);
    }
}
