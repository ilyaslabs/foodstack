package io.github.ilyaslabs.foodstack.cart.core.application;

import java.util.List;

/**
 * An item to add.
 *
 * @param storeId the branch it is from
 * @param itemId menu item
 * @param quantity how many, 1 to 50
 * @param optionIds chosen options
 * @param note note for the kitchen, up to 200 characters, or {@code null}
 * @param replaceOtherStore empty a cart from another branch first (the customer agreed, PRD CO-1)
 */
public record AddLine(String storeId, String itemId, int quantity, List<String> optionIds, String note,
        boolean replaceOtherStore) {
}
