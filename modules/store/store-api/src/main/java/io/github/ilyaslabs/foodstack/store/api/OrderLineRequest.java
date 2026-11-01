package io.github.ilyaslabs.foodstack.store.api;

import java.util.List;

/**
 * One cart line to price.
 *
 * @param itemId menu item
 * @param quantity how many, 1 or more
 * @param optionIds chosen modifier options, across all of the item's groups
 */
public record OrderLineRequest(String itemId, int quantity, List<String> optionIds) {
}
