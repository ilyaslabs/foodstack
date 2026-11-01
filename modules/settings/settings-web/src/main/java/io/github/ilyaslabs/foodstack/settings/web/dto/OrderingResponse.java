package io.github.ilyaslabs.foodstack.settings.web.dto;

/**
 * Ordering rules. Rates are basis points (100 = 1%), amounts are minor units.
 *
 * @param cashEnabled customers may pay cash
 * @param cardEnabled customers may pay by card (checkout also needs a configured gateway)
 * @param serviceFeeMinor fixed service fee
 * @param serviceFeeBasisPoints service fee share of the food subtotal
 * @param taxBasisPoints tax on the food subtotal after discounts
 * @param tipsEnabled customers may tip
 * @param acceptTimeoutSeconds time a store has to accept
 * @param version for {@code If-Match}
 * @param updatedAt last change in epoch seconds, or {@code null}
 */
public record OrderingResponse(boolean cashEnabled, boolean cardEnabled, long serviceFeeMinor, int serviceFeeBasisPoints,
        int taxBasisPoints, boolean tipsEnabled, int acceptTimeoutSeconds, long version, Long updatedAt) {
}
