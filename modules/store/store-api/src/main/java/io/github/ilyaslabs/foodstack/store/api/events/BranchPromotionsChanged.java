package io.github.ilyaslabs.foodstack.store.api.events;

import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;

/**
 * The brand owner allowed or stopped branch managers creating promotions for their own branch
 * (PRD BB-11). The promotion module keeps the switch for its access checks. Topic
 * {@code foodstack.store.events}, type {@code foodstack.store.BranchPromotionsChanged.v1}.
 *
 * @param brandId the brand
 * @param allowed whether branch managers may create promotions for their branch
 * @param version the brand's new version, so an older event never overwrites a newer switch
 * @param changedBy user who made the change
 */
public record BranchPromotionsChanged(String brandId, boolean allowed, long version, String changedBy) implements ModuleEvent {

    @Override
    public String subject() {
        return this.brandId;
    }
}
