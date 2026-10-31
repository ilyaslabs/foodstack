package io.github.ilyaslabs.foodstack.geo.core.application;

import java.util.List;

import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;

/**
 * One zone to save. Either a ring ({@code center} and {@code radiusMeters}, the common case: the
 * owner knows the distance of each area) or a drawn {@code polygon}.
 *
 * @param name shown to the owner, 1 to 60 characters
 * @param center centre of a ring zone, usually the branch
 * @param radiusMeters radius of a ring zone, 200 m to 50 km
 * @param polygon outline of a drawn zone, 3 to 200 points
 * @param chargeMinor fixed delivery charge, 0 or more
 * @param minOrderMinor minimum subtotal, 0 for none
 * @param freeDeliveryAboveMinor subtotal from which delivery is free, or {@code null}
 */
public record ZoneInput(String name, GeoPoint center, Integer radiusMeters, List<GeoPoint> polygon, long chargeMinor,
        long minOrderMinor, Long freeDeliveryAboveMinor) {
}
