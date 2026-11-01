package io.github.ilyaslabs.foodstack.search.core.engine;

import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import io.github.ilyaslabs.foodstack.store.api.TimeRange;

/**
 * A live branch as the search index keeps it.
 *
 * @param storeId branch id, the document id
 * @param slug web address of the branch, or {@code null}
 * @param brandId brand id, results are collapsed on it
 * @param brandName brand name
 * @param branchName branch name, or {@code null}
 * @param branchCount branches of the brand
 * @param description translated description
 * @param cuisines cuisine tags
 * @param logoUrls logo variant URLs
 * @param coverUrls cover variant URLs
 * @param location map position
 * @param area neighbourhood
 * @param city city
 * @param deliveryMode SELF or OPERATOR
 * @param pickupEnabled whether customers may collect
 * @param temporarilyClosed the owner's safety switch
 * @param prepMinutes average preparation time
 * @param timezone IANA zone of the hours
 * @param openingHours ranges per weekday (MONDAY to SUNDAY)
 * @param closedDates holiday dates, ISO
 * @param dishNames names of available dishes per language
 * @param dietaryTags dietary tags on the menu
 * @param deliveryArea polygons the branch delivers to; empty when it does not deliver
 * @param version source version
 */
public record IndexedStore(String storeId, String slug, String brandId, String brandName, String branchName, int branchCount,
        Map<String, String> description, List<String> cuisines, Map<String, String> logoUrls, Map<String, String> coverUrls,
        GeoPoint location, String area, String city, String deliveryMode, boolean pickupEnabled, boolean temporarilyClosed,
        int prepMinutes, String timezone, Map<String, List<TimeRange>> openingHours, List<String> closedDates,
        Map<String, List<String>> dishNames, List<String> dietaryTags, List<List<GeoPoint>> deliveryArea, long version) {
}
