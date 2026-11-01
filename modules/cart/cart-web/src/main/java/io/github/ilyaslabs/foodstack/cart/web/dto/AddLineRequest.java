package io.github.ilyaslabs.foodstack.cart.web.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /api/v1/carts/me/lines}.
 *
 * @param storeId the branch the item is from
 * @param itemId menu item
 * @param quantity 1 to 50
 * @param optionIds chosen options across the item's groups
 * @param note note for the kitchen, up to 200 characters
 * @param replaceOtherStore empty a cart from another restaurant first (the customer agreed)
 */
public record AddLineRequest(@NotBlank String storeId, @NotBlank String itemId, int quantity, List<String> optionIds,
        String note, Boolean replaceOtherStore) {
}
