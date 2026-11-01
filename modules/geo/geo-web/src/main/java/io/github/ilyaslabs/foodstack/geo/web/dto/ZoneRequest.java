package io.github.ilyaslabs.foodstack.geo.web.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One delivery zone: a ring ({@code center} + {@code radiusMeters}) or a drawn {@code polygon}.
 *
 * @param name shown to the owner
 * @param center centre of a ring zone
 * @param radiusMeters radius of a ring zone
 * @param polygon outline of a drawn zone
 * @param chargeMinor fixed delivery charge in minor units
 * @param minOrderMinor minimum subtotal, 0 for none
 * @param freeDeliveryAboveMinor free-delivery threshold, or {@code null}
 */
public record ZoneRequest(@NotBlank @Size(max = 60) String name, @Valid LatLng center, Integer radiusMeters,
        List<@Valid LatLng> polygon, long chargeMinor, long minOrderMinor, Long freeDeliveryAboveMinor) {
}
