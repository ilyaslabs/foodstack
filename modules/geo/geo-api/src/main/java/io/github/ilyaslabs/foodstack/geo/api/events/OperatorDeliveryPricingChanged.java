package io.github.ilyaslabs.foodstack.geo.api.events;

import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;

/**
 * The operator's delivery pricing changed. Type {@code foodstack.geo.OperatorDeliveryPricingChanged.v1};
 * search refreshes the area of every operator-delivery branch, because the maximum distance may differ.
 *
 * @param version the pricing's new version
 * @param changedBy user who made the change
 */
public record OperatorDeliveryPricingChanged(long version, String changedBy) implements ModuleEvent {

    @Override
    public String subject() {
        return "operator-delivery-pricing";
    }
}
