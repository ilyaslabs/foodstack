package io.github.ilyaslabs.foodstack.geo.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

/**
 * A point on the map.
 *
 * @param lat latitude
 * @param lng longitude
 */
public record LatLng(@DecimalMin("-90") @DecimalMax("90") double lat, @DecimalMin("-180") @DecimalMax("180") double lng) {
}
