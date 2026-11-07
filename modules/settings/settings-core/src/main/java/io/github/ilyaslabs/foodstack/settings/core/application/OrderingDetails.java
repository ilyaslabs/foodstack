package io.github.ilyaslabs.foodstack.settings.core.application;

import java.time.Instant;

/**
 * Ordering settings (see {@code OrderingView} for the meaning of each field).
 *
 * @param cashEnabled cash payments allowed
 * @param cardEnabled card payments allowed
 * @param serviceFeeMinor fixed service fee
 * @param serviceFeeBasisPoints service fee share of the food subtotal
 * @param taxBasisPoints tax on the food subtotal after discounts
 * @param tipsEnabled tips allowed
 * @param acceptTimeoutSeconds store accept time
 * @param version current version
 * @param updatedAt last change, or {@code null}
 * @param priceApprovalRequired price changes wait for approval
 * @param directCollectionEnabled branches may collect card payments themselves
 * @param creditLimitMinor default credit limit of a direct-collecting branch
 */
public record OrderingDetails(boolean cashEnabled, boolean cardEnabled, long serviceFeeMinor, int serviceFeeBasisPoints,
        int taxBasisPoints, boolean tipsEnabled, int acceptTimeoutSeconds, long version, Instant updatedAt,
        boolean priceApprovalRequired, boolean directCollectionEnabled, long creditLimitMinor, String cardStyle,
        boolean savedCardsEnabled) {

    /** Details without card style and saved cards: hosted page, no saved cards. */
    public OrderingDetails(boolean cashEnabled, boolean cardEnabled, long serviceFeeMinor, int serviceFeeBasisPoints,
            int taxBasisPoints, boolean tipsEnabled, int acceptTimeoutSeconds, long version, Instant updatedAt,
            boolean priceApprovalRequired, boolean directCollectionEnabled, long creditLimitMinor) {
        this(cashEnabled, cardEnabled, serviceFeeMinor, serviceFeeBasisPoints, taxBasisPoints, tipsEnabled,
                acceptTimeoutSeconds, version, updatedAt, priceApprovalRequired, directCollectionEnabled, creditLimitMinor,
                "HOSTED_PAGE", false);
    }
}
