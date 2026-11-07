package io.github.ilyaslabs.foodstack.geo.web.dto;

import java.util.List;
import java.util.Map;

/**
 * A city.
 *
 * @param id city id
 * @param name name by language
 * @param centre middle of the city
 * @param active whether new branches and riders can be put in it
 * @param pricing the city's own operator delivery pricing, or {@code null} when it uses the operator's
 * @param version current version (also the ETag)
 * @param updatedAt UTC epoch seconds, or {@code null}
 */
public record CityResponse(String id, Map<String, String> name, LatLng centre, boolean active, Pricing pricing, long version,
        Long updatedAt) {

    /**
     * @param baseChargeMinor charge for any delivery
     * @param perKmMinor added per km of road distance
     * @param maxDistanceMeters farthest delivery
     * @param minOrderMinor smallest basket subtotal, 0 for none
     * @param freeDeliveryAboveMinor subtotal from which delivery is free, or {@code null}
     * @param peakMultiplierPercent peak charge as a percentage of the normal one
     * @param peakWindows when peak hours apply
     */
    public record Pricing(long baseChargeMinor, long perKmMinor, int maxDistanceMeters, long minOrderMinor,
            Long freeDeliveryAboveMinor, int peakMultiplierPercent, List<PeakWindowDto> peakWindows) {
    }
}
