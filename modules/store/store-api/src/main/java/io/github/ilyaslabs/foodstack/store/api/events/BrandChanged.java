package io.github.ilyaslabs.foodstack.store.api.events;

import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;

/**
 * The brand's name, images, cuisines or commission changed. Search re-indexes every branch of the brand. Topic
 * {@code foodstack.store.events}, type {@code foodstack.store.BrandChanged.v1}.
 *
 * @param brandId the brand
 * @param version its new version
 * @param changedBy user who made the change
 */
public record BrandChanged(String brandId, long version, String changedBy) implements ModuleEvent {

    @Override
    public String subject() {
        return this.brandId;
    }
}
