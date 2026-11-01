package io.github.ilyaslabs.foodstack.store.core.application;

import java.util.List;
import java.util.Map;

/**
 * A branch's menu as customers see it: the brand menu filtered to the branch and merged with its
 * sold-out marks (TDD 5.7). Cached per branch.
 *
 * @param storeId the branch
 * @param brandId its brand
 * @param currency ISO 4217 code of the prices
 * @param sections non-empty sections in order
 */
public record EffectiveMenu(String storeId, String brandId, String currency, List<Section> sections) {

    /**
     * @param id section id
     * @param name translated name
     * @param items items in order
     */
    public record Section(String id, Map<String, String> name, List<Item> items) {
    }

    /**
     * @param id item id
     * @param name translated name
     * @param description translated description
     * @param priceMinor price at this branch
     * @param imageUrls image variant URLs
     * @param dietaryTags dietary tags
     * @param soldOut sold out at this branch
     * @param modifierGroups choices
     */
    public record Item(String id, Map<String, String> name, Map<String, String> description, long priceMinor,
            Map<String, String> imageUrls, List<String> dietaryTags, boolean soldOut, List<Group> modifierGroups) {
    }

    /**
     * @param id group id
     * @param name translated name
     * @param minSelect fewest choices
     * @param maxSelect most choices
     * @param options choices
     */
    public record Group(String id, Map<String, String> name, int minSelect, int maxSelect, List<Option> options) {
    }

    /**
     * @param id option id
     * @param name translated name
     * @param priceDeltaMinor added to the item price
     * @param soldOut sold out at this branch
     */
    public record Option(String id, Map<String, String> name, long priceDeltaMinor, boolean soldOut) {
    }
}
