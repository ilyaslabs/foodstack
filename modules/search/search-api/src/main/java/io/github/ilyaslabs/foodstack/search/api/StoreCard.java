package io.github.ilyaslabs.foodstack.search.api;

import java.util.List;
import java.util.Map;

/**
 * A branch in search results.
 *
 * @param storeId branch id
 * @param slug web address of the branch ({@code /stores/<slug>}), or {@code null}
 * @param brandId brand id
 * @param brandName brand name
 * @param branchName branch name, or {@code null}
 * @param branchCount branches of the brand
 * @param description translated brand description
 * @param cuisines cuisine tags
 * @param logoUrls logo variant URLs
 * @param coverUrls cover variant URLs
 * @param area neighbourhood
 * @param city city
 * @param distanceMeters straight-line distance from the customer
 * @param deliveryMode SELF or OPERATOR
 * @param pickupEnabled whether customers may collect
 * @param openNow whether it takes orders right now
 * @param temporarilyClosed the owner's safety switch
 * @param prepMinutes average preparation time
 * @param dietaryTags dietary tags found on its menu
 * @param ratingCount published reviews of the branch (PRD RV-4)
 * @param ratingAverage their mean, one decimal; 0 with none
 */
public record StoreCard(String storeId, String slug, String brandId, String brandName, String branchName, int branchCount,
        Map<String, String> description, List<String> cuisines, Map<String, String> logoUrls, Map<String, String> coverUrls,
        String area, String city, long distanceMeters, String deliveryMode, boolean pickupEnabled, boolean openNow,
        boolean temporarilyClosed, int prepMinutes, List<String> dietaryTags, long ratingCount, double ratingAverage) {

    public StoreCard(String storeId, String slug, String brandId, String brandName, String branchName, int branchCount,
            Map<String, String> description, List<String> cuisines, Map<String, String> logoUrls, Map<String, String> coverUrls,
            String area, String city, long distanceMeters, String deliveryMode, boolean pickupEnabled, boolean openNow,
            boolean temporarilyClosed, int prepMinutes, List<String> dietaryTags) {
        this(storeId, slug, brandId, brandName, branchName, branchCount, description, cuisines, logoUrls, coverUrls, area, city,
                distanceMeters, deliveryMode, pickupEnabled, openNow, temporarilyClosed, prepMinutes, dietaryTags, 0, 0);
    }
}
