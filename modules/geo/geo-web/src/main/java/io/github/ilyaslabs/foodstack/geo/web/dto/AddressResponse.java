package io.github.ilyaslabs.foodstack.geo.web.dto;

/**
 * An address found by search or by a map pin.
 *
 * @param label one line
 * @param lat latitude
 * @param lng longitude
 */
public record AddressResponse(String label, double lat, double lng) {
}
