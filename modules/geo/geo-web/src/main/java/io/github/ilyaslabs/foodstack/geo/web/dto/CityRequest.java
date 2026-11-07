package io.github.ilyaslabs.foodstack.geo.web.dto;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotNull;

/**
 * {@code POST /api/v1/geo/cities}, or {@code PUT /api/v1/geo/cities/{id}} with {@code If-Match}.
 *
 * @param name name by language
 * @param centre middle of the city
 * @param active whether new branches and riders can be put in it
 * @param pricing the city's own operator delivery pricing, or {@code null} to use the operator's
 */
public record CityRequest(@NotNull Map<String, String> name, @NotNull LatLng centre, boolean active, Pricing pricing) {

    /**
     * @param baseChargeMinor charge for any delivery
     * @param perKmMinor added per km of road distance
     * @param maxDistanceMeters farthest delivery
     * @param minOrderMinor smallest basket subtotal, {@code null} or 0 for none
     * @param freeDeliveryAboveMinor subtotal from which delivery is free, {@code null} for never
     * @param peakMultiplierPercent peak charge as a percentage of the normal one, {@code null} for 100
     * @param peakWindows when peak hours apply
     */
    public record Pricing(long baseChargeMinor, long perKmMinor, int maxDistanceMeters, Long minOrderMinor,
            Long freeDeliveryAboveMinor, Integer peakMultiplierPercent, List<PeakWindowDto> peakWindows) {
    }
}
