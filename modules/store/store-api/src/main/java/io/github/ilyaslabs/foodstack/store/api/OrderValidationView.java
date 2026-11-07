package io.github.ilyaslabs.foodstack.store.api;

import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.ilyaslabs.foodstack.geo.api.DeliveryMode;
import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;

/**
 * Cart lines priced at the branch's current prices, with what is wrong with them, and the branch
 * details an order snapshots (TDD 5.3). Amounts are in minor units of {@code currency}.
 *
 * @param store the branch as it is now
 * @param currency ISO 4217 code of the amounts
 * @param lines priced lines in request order (lines with problems are priced as far as possible)
 * @param subtotalMinor sum of the line totals
 * @param problems what stops the lines being ordered; empty when every line can be ordered
 */
public record OrderValidationView(StoreSnapshot store, String currency, List<PricedLine> lines, long subtotalMinor,
        List<LineProblem> problems) {

    /** The item no longer exists at this branch. */
    public static final String ITEM_NOT_FOUND = "ITEM_NOT_FOUND";

    /** The item is sold out at this branch. */
    public static final String ITEM_SOLD_OUT = "ITEM_SOLD_OUT";

    /** A chosen option no longer exists. */
    public static final String OPTION_NOT_FOUND = "OPTION_NOT_FOUND";

    /** A chosen option is sold out. */
    public static final String OPTION_SOLD_OUT = "OPTION_SOLD_OUT";

    /** Too few or too many choices in a group; {@code detail} is the group id. */
    public static final String CHOICES_INVALID = "CHOICES_INVALID";

    /** Quantity is below 1 or above the limit. */
    public static final String QUANTITY_INVALID = "QUANTITY_INVALID";

    /**
     * The branch, for the order snapshot.
     *
     * @param storeId branch id
     * @param slug web address, or {@code null}
     * @param brandId brand id
     * @param brandName brand name
     * @param branchName branch name
     * @param logoUrls brand logo variant URLs
     * @param location where to collect from, or {@code null} when not set
     * @param addressLine street, area and city in one line
     * @param city city of the address, or {@code null}
     * @param deliveryMode who delivers
     * @param pickupEnabled customers may collect
     * @param live the branch is published
     * @param openNow open right now (hours, closures and the temporary-close switch)
     * @param prepMinutes average preparation time
     * @param commissionBasisPoints operator commission percentage in basis points
     * @param commissionFixedMinor operator commission per order
     * @param ownerUserIds users who run the branch: its own owners and managers and the brand's owners
     * @param timezone IANA zone of the opening hours
     * @param cityId the operator's city the branch is in, or {@code null}
     * @param branchOwnerUserIds the branch's own owners (a franchisee is paid as the branch, PRD BB-6)
     */
    public record StoreSnapshot(String storeId, String slug, String brandId, String brandName, String branchName,
            Map<String, String> logoUrls, GeoPoint location, String addressLine, String city, DeliveryMode deliveryMode,
            boolean pickupEnabled, boolean live, boolean openNow, int prepMinutes, int commissionBasisPoints,
            long commissionFixedMinor, Set<String> ownerUserIds, String timezone, String cityId,
            Set<String> branchOwnerUserIds) {

        /** A branch without a city, whose owners all manage this branch only. */
        public StoreSnapshot(String storeId, String slug, String brandId, String brandName, String branchName,
                Map<String, String> logoUrls, GeoPoint location, String addressLine, String city, DeliveryMode deliveryMode,
                boolean pickupEnabled, boolean live, boolean openNow, int prepMinutes, int commissionBasisPoints,
                long commissionFixedMinor, Set<String> ownerUserIds, String timezone) {
            this(storeId, slug, brandId, brandName, branchName, logoUrls, location, addressLine, city, deliveryMode,
                    pickupEnabled, live, openNow, prepMinutes, commissionBasisPoints, commissionFixedMinor, ownerUserIds,
                    timezone, null, ownerUserIds);
        }
    }

    /**
     * A priced line.
     *
     * @param itemId menu item
     * @param name item name by language
     * @param quantity how many
     * @param unitPriceMinor item price plus chosen option prices
     * @param options chosen options in menu order
     * @param lineTotalMinor unit price times quantity
     */
    public record PricedLine(String itemId, Map<String, String> name, int quantity, long unitPriceMinor,
            List<PricedOption> options, long lineTotalMinor) {
    }

    /**
     * A chosen option.
     *
     * @param groupId modifier group
     * @param groupName group name by language
     * @param optionId option
     * @param name option name by language
     * @param priceDeltaMinor added to the item price
     */
    public record PricedOption(String groupId, Map<String, String> groupName, String optionId, Map<String, String> name,
            long priceDeltaMinor) {
    }

    /**
     * Why a line cannot be ordered as it is.
     *
     * @param lineIndex index of the line in the request
     * @param itemId its item
     * @param code one of the constants of {@link OrderValidationView}
     * @param detail the option or group concerned, or {@code null}
     */
    public record LineProblem(int lineIndex, String itemId, String code, String detail) {
    }
}
