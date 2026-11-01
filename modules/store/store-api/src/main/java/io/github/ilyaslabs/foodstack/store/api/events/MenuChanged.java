package io.github.ilyaslabs.foodstack.store.api.events;

import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;

/**
 * The brand menu changed (sections, items, prices). Search refreshes dish names of every branch. Topic
 * {@code foodstack.store.events}, type {@code foodstack.store.MenuChanged.v1}.
 *
 * @param brandId the brand whose menu changed
 * @param changedBy user who made the change
 */
public record MenuChanged(String brandId, String changedBy) implements ModuleEvent {

    @Override
    public String subject() {
        return this.brandId;
    }
}
