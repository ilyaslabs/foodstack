package io.github.ilyaslabs.foodstack.geo.api;

import java.util.List;

/**
 * Where a branch delivers: a set of simple polygons (outer rings, first point not repeated).
 *
 * @param storeId the branch
 * @param polygons the area; empty when the branch delivers nowhere
 */
public record DeliveryAreaView(String storeId, List<List<GeoPoint>> polygons) {

    public boolean isEmpty() {
        return this.polygons == null || this.polygons.isEmpty();
    }
}
