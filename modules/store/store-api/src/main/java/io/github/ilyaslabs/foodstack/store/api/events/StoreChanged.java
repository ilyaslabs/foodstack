package io.github.ilyaslabs.foodstack.store.api.events;

import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;

/**
 * A branch was created or changed (profile, hours, delivery mode, status). Search re-reads the listing. Topic
 * {@code foodstack.store.events}, type {@code foodstack.store.StoreChanged.v1}.
 *
 * @param storeId the branch
 * @param brandId its brand
 * @param version its new version
 * @param changedBy user who made the change
 */
public record StoreChanged(String storeId, String brandId, long version, String changedBy) implements ModuleEvent {

    @Override
    public String subject() {
        return this.storeId;
    }
}
