package io.github.ilyaslabs.foodstack.store.api.events;

import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;

/**
 * An item or modifier option was marked sold out or available again at one branch (PRD ST-4). Topic
 * {@code foodstack.store.events}, type {@code foodstack.store.ItemAvailabilityChanged.v1}.
 *
 * @param storeId the branch
 * @param itemId the menu item
 * @param optionId the modifier option, or {@code null} for the whole item
 * @param soldOut whether it is sold out now
 * @param changedBy user who made the change
 */
public record ItemAvailabilityChanged(String storeId, String itemId, String optionId, boolean soldOut, String changedBy) implements ModuleEvent {

    @Override
    public String subject() {
        return this.storeId;
    }
}
