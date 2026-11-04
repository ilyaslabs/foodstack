package io.github.ilyaslabs.foodstack.rider.web;

import java.util.List;

import io.github.ilyaslabs.foodstack.platform.core.GeoCells;
import io.github.ilyaslabs.moduleswitch.ModuleException;

/** The map cells of a live map's view; a view wider than the cap must zoom in (TDD 7.5). */
final class MapView {

    private MapView() {
    }

    static List<String> cells(double south, double west, double north, double east) {
        try {
            return GeoCells.within(south, west, north, east);
        }
        catch (IllegalArgumentException ex) {
            throw new ModuleException.BadRequest("Zoom in to follow this area live", "MAP_VIEW_TOO_WIDE");
        }
    }
}
