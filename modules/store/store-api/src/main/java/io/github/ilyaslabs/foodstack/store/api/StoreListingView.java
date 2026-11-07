package io.github.ilyaslabs.foodstack.store.api;

import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.geo.api.DeliveryMode;
import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;

/**
 * A branch as discovery sees it.
 *
 * @param storeId the branch
 * @param slug web address of the branch ({@code /stores/<slug>}), or {@code null} until assigned
 * @param brandId its brand
 * @param brandName brand name
 * @param branchName branch name, for example "Gulberg"
 * @param branchCount how many branches the brand has (the brand is hidden in the UI while it has one)
 * @param description brand description by language tag
 * @param cuisines cuisine tags, lower case
 * @param logoUrls brand logo variant URLs, empty without a logo
 * @param coverUrls brand cover variant URLs, empty without a cover
 * @param location where the branch is, or {@code null} while not set
 * @param area neighbourhood or area of the address, for the card
 * @param city city of the address
 * @param deliveryMode who delivers
 * @param pickupEnabled whether customers may collect
 * @param status visibility
 * @param temporarilyClosed the owner's safety switch (PRD ST-2a)
 * @param prepMinutes average preparation time
 * @param timezone IANA zone of the opening hours
 * @param openingHours ranges per day, keyed {@code MONDAY} to {@code SUNDAY}
 * @param closedDates holiday closures as ISO dates {@code yyyy-MM-dd}
 * @param dishNames names of available dishes by language tag, for full-text search
 * @param dietaryTags dietary tags found on the menu
 * @param version the branch's version, to drop out-of-order updates
 * @param cityId the operator's city, or {@code null}
 * @param searchBoost the operator's boost of the brand in search, 0 (none) to 3
 */
public record StoreListingView(String storeId, String slug, String brandId, String brandName, String branchName, int branchCount,
        Map<String, String> description, List<String> cuisines, Map<String, String> logoUrls, Map<String, String> coverUrls,
        GeoPoint location, String area, String city, DeliveryMode deliveryMode, boolean pickupEnabled, StoreStatus status,
        boolean temporarilyClosed, int prepMinutes, String timezone, Map<String, List<TimeRange>> openingHours,
        List<String> closedDates, Map<String, List<String>> dishNames, List<String> dietaryTags, long version,
        String cityId, int searchBoost) {

    /** A listing without a search boost. */
    public StoreListingView(String storeId, String slug, String brandId, String brandName, String branchName,
            int branchCount, Map<String, String> description, List<String> cuisines, Map<String, String> logoUrls,
            Map<String, String> coverUrls, GeoPoint location, String area, String city, DeliveryMode deliveryMode,
            boolean pickupEnabled, StoreStatus status, boolean temporarilyClosed, int prepMinutes, String timezone,
            Map<String, List<TimeRange>> openingHours, List<String> closedDates, Map<String, List<String>> dishNames,
            List<String> dietaryTags, long version, String cityId) {
        this(storeId, slug, brandId, brandName, branchName, branchCount, description, cuisines, logoUrls, coverUrls,
                location, area, city, deliveryMode, pickupEnabled, status, temporarilyClosed, prepMinutes, timezone,
                openingHours, closedDates, dishNames, dietaryTags, version, cityId, 0);
    }
}
