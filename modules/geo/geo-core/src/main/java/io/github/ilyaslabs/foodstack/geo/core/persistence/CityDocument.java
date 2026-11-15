package io.github.ilyaslabs.foodstack.geo.core.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.geo.core.application.PeakWindow;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A city in {@code geo_cities} (unsharded: a deployment serves one country, so a few hundred at
 * most, rarely written). Read through the Redis cache.
 *
 * @param id city id (UUIDv7)
 * @param name name by language
 * @param centreLat latitude of the centre
 * @param centreLng longitude of the centre
 * @param active whether new branches and riders can be put in it
 * @param pricing the city's own operator delivery pricing, or {@code null} to use the operator's
 * @param updatedAt last change
 * @param updatedBy who changed it
 * @param version optimistic lock
 */
@Document("geo_cities")
public record CityDocument(@Id String id, Map<String, String> name, double centreLat, double centreLng, boolean active,
        Pricing pricing, Instant updatedAt, String updatedBy, @Version Long version) {

    /**
     * Operator delivery pricing for one city (PRD DR-1b, per-city override). Whether operator
     * delivery is on at all stays the operator's single switch.
     *
     * @param baseChargeMinor charge for any delivery
     * @param perKmMinor added per km of road distance
     * @param maxDistanceMeters farthest delivery
     * @param minOrderMinor smallest basket subtotal, 0 for none
     * @param freeDeliveryAboveMinor subtotal from which delivery is free, or {@code null}
     * @param peakMultiplierPercent peak charge as a percentage of the normal one (100 to 300)
     * @param peakWindows when peak hours apply, in each branch's local time
     */
    public record Pricing(long baseChargeMinor, long perKmMinor, int maxDistanceMeters, long minOrderMinor,
            Long freeDeliveryAboveMinor, int peakMultiplierPercent, List<PeakWindow> peakWindows) {
    }
}
