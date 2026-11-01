package io.github.ilyaslabs.foodstack.geo.web.dto;

import java.util.List;

/**
 * A saved zone.
 *
 * @param id zone id
 * @param name shown to the owner
 * @param center centre of a ring zone, or {@code null}
 * @param radiusMeters radius of a ring zone, or {@code null}
 * @param polygon the outline
 * @param chargeMinor delivery charge
 * @param minOrderMinor minimum subtotal
 * @param freeDeliveryAboveMinor free-delivery threshold, or {@code null}
 */
public record ZoneResponse(String id, String name, LatLng center, Integer radiusMeters, List<LatLng> polygon,
        long chargeMinor, long minOrderMinor, Long freeDeliveryAboveMinor) {
}
