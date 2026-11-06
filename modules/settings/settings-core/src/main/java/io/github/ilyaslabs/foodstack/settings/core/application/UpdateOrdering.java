package io.github.ilyaslabs.foodstack.settings.core.application;

/**
 * New ordering settings.
 *
 * @param cashEnabled cash payments allowed
 * @param cardEnabled card payments allowed
 * @param serviceFeeMinor fixed service fee, 0 or more
 * @param serviceFeeBasisPoints service fee share, 0 to 5000 (50%)
 * @param taxBasisPoints tax rate, 0 to 10000
 * @param tipsEnabled tips allowed
 * @param acceptTimeoutSeconds 60 to 1800
 */
public record UpdateOrdering(boolean cashEnabled, boolean cardEnabled, long serviceFeeMinor, int serviceFeeBasisPoints,
        int taxBasisPoints, boolean tipsEnabled, int acceptTimeoutSeconds, boolean priceApprovalRequired) {

    /** Rules without price approval. */
    public UpdateOrdering(boolean cashEnabled, boolean cardEnabled, long serviceFeeMinor, int serviceFeeBasisPoints,
            int taxBasisPoints, boolean tipsEnabled, int acceptTimeoutSeconds) {
        this(cashEnabled, cardEnabled, serviceFeeMinor, serviceFeeBasisPoints, taxBasisPoints, tipsEnabled,
                acceptTimeoutSeconds, false);
    }
}
