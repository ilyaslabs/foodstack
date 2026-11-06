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
 */
public record OrderingRequest(boolean cashEnabled, boolean cardEnabled, long serviceFeeMinor, int serviceFeeBasisPoints,
        int taxBasisPoints, boolean tipsEnabled, int acceptTimeoutSeconds, boolean priceApprovalRequired) {
}
