package io.github.ilyaslabs.foodstack.geo.api.events;

import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;

/**
 * A city was added, renamed, moved, switched on or off, or its delivery pricing changed. Type
 * {@code foodstack.geo.CityChanged.v1}.
 *
 * @param cityId the city
 * @param version the city's new version
 * @param changedBy user who made the change
 */
public record CityChanged(String cityId, long version, String changedBy) implements ModuleEvent {

    @Override
    public String subject() {
        return this.cityId;
    }
}
