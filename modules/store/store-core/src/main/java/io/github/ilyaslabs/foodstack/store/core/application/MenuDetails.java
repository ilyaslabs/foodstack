package io.github.ilyaslabs.foodstack.store.core.application;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A brand menu as the editor sees it.
 *
 * @param brandId the brand
 * @param currency ISO 4217 code of the prices
 * @param languages enabled languages, default first
 * @param sections sections in order, each with its items
 */
public record MenuDetails(String brandId, String currency, List<String> languages, List<Section> sections) {

    /**
     * A section.
     *
     * @param id section id
     * @param name translated name
     * @param sortOrder position
     * @param version current version
     * @param items items in order
     */
    public record Section(String id, Map<String, String> name, int sortOrder, long version, List<Item> items) {
    }

    /**
     * An item.
     *
     * @param id item id
     * @param sectionId its section
     * @param name translated name
     * @param description translated description
     * @param priceMinor price
     * @param imageMediaId image media id, or {@code null}
     * @param imageUrls image variant URLs
     * @param dietaryTags dietary tags
     * @param modifierGroups choices
     * @param branchIds branches that sell it, or {@code null} for all
     * @param sortOrder position
     * @param version current version
     */
    public record Item(String id, String sectionId, Map<String, String> name, Map<String, String> description,
            long priceMinor, String imageMediaId, Map<String, String> imageUrls, List<String> dietaryTags,
            List<ModifierGroup> modifierGroups, Set<String> branchIds, int sortOrder, long version) {
    }
}
