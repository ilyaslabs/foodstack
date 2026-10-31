package io.github.ilyaslabs.foodstack.store.core.application;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A menu item to save (PRD ST-3).
 *
 * @param sectionId its section
 * @param name translated name, required in the default language
 * @param description translated description
 * @param priceMinor price in minor units
 * @param imageMediaId a processed MENU_ITEM upload, or {@code null}
 * @param dietaryTags tags from {@link MenuService#DIETARY_TAGS}
 * @param modifierGroups choices; missing ids are generated
 * @param branchIds branches that sell it, or {@code null} for every branch
 * @param sortOrder position in its section
 */
public record MenuItemInput(String sectionId, Map<String, String> name, Map<String, String> description, long priceMinor,
        String imageMediaId, List<String> dietaryTags, List<ModifierGroup> modifierGroups, Set<String> branchIds,
        int sortOrder) {
}
