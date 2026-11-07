package io.github.ilyaslabs.foodstack.settings.web.dto;

/**
 * {@code PUT /api/v1/settings/ordering} with {@code If-Match}. Rates are basis points (100 = 1%).
 *
 * @param cashEnabled customers may pay cash
 * @param cardEnabled customers may pay by card
 * @param serviceFeeMinor fixed service fee in minor units
 * @param serviceFeeBasisPoints service fee share of the food subtotal, 0 to 5000
 * @param taxBasisPoints tax on the food subtotal after discounts, 0 to 10000
 * @param tipsEnabled customers may tip
 * @param acceptTimeoutSeconds time a store has to accept, 60 to 1800
 * @param priceApprovalRequired price changes wait for approval; {@code null} keeps it
 * @param directCollectionEnabled branches may collect card payments themselves; {@code null} keeps it
 * @param creditLimitMinor default credit limit of a direct-collecting branch; {@code null} keeps it
 */
public record OrderingRequest(boolean cashEnabled, boolean cardEnabled, long serviceFeeMinor, int serviceFeeBasisPoints,
        int taxBasisPoints, boolean tipsEnabled, int acceptTimeoutSeconds, Boolean priceApprovalRequired,
        Boolean directCollectionEnabled, Long creditLimitMinor, String cardStyle, Boolean savedCardsEnabled) {

    /** A request from a client that does not know card style and saved cards: both stay as they are. */
    public OrderingRequest(boolean cashEnabled, boolean cardEnabled, long serviceFeeMinor, int serviceFeeBasisPoints,
            int taxBasisPoints, boolean tipsEnabled, int acceptTimeoutSeconds, Boolean priceApprovalRequired,
            Boolean directCollectionEnabled, Long creditLimitMinor) {
        this(cashEnabled, cardEnabled, serviceFeeMinor, serviceFeeBasisPoints, taxBasisPoints, tipsEnabled,
                acceptTimeoutSeconds, priceApprovalRequired, directCollectionEnabled, creditLimitMinor, null, null);
    }

    /** A request from a client that does not know direct collection: both stay as they are. */
    public OrderingRequest(boolean cashEnabled, boolean cardEnabled, long serviceFeeMinor, int serviceFeeBasisPoints,
            int taxBasisPoints, boolean tipsEnabled, int acceptTimeoutSeconds, Boolean priceApprovalRequired) {
        this(cashEnabled, cardEnabled, serviceFeeMinor, serviceFeeBasisPoints, taxBasisPoints, tipsEnabled,
                acceptTimeoutSeconds, priceApprovalRequired, null, null, null, null);
    }
}
