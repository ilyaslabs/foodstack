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
 */
public record OrderingView(boolean cashEnabled, boolean cardEnabled, long serviceFeeMinor, int serviceFeeBasisPoints,
        int taxBasisPoints, boolean tipsEnabled, int acceptTimeoutSeconds, long version) {
}
