package io.github.ilyaslabs.foodstack.geo.api.events;

import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;

/**
 * A branch's delivery zones were replaced. Topic {@code foodstack.geo.events}, type
 * {@code foodstack.geo.ZoneChanged.v1}; search re-reads the delivery area.
 *
 * @param storeId the branch
 * @param zoneCount how many zones it has now
 * @param changedBy user who made the change
 */
public record ZoneChanged(String storeId, int zoneCount, String changedBy) implements ModuleEvent {

    @Override
    public String subject() {
        return this.storeId;
    }
}
