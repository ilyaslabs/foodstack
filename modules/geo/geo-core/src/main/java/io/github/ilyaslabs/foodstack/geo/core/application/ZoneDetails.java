package io.github.ilyaslabs.foodstack.geo.core.application;

import java.time.Instant;
import java.util.List;

import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;

/**
 * A saved zone.
 *
 * @param id zone id
 * @param name shown to the owner
 * @param center centre of a ring zone, or {@code null}
 * @param radiusMeters radius of a ring zone, or {@code null}
 * @param polygon the zone's outline (a ring is stored as a polygon too)
 * @param chargeMinor delivery charge
 * @param minOrderMinor minimum subtotal
 * @param freeDeliveryAboveMinor free-delivery threshold, or {@code null}
 * @param updatedAt last change
 */
public record ZoneDetails(String id, String name, GeoPoint center, Integer radiusMeters, List<GeoPoint> polygon,
        long chargeMinor, long minOrderMinor, Long freeDeliveryAboveMinor, Instant updatedAt) {
}
