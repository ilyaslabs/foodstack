package io.github.ilyaslabs.foodstack.store.api.events;

import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;

/**
 * The owner's temporarily-closed switch changed (PRD ST-2a). Topic
 * {@code foodstack.store.events}, type {@code foodstack.store.StoreAvailabilityChanged.v1}.
 *
 * @param storeId the branch
 * @param temporarilyClosed whether it is closed now
 * @param reason optional reason shown to customers
 * @param changedBy user who made the change
 */
public record StoreAvailabilityChanged(String storeId, boolean temporarilyClosed, String reason, String changedBy) implements ModuleEvent {

    @Override
    public String subject() {
        return this.storeId;
    }
}
