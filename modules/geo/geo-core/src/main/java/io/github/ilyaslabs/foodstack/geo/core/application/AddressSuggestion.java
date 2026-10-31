package io.github.ilyaslabs.foodstack.geo.core.application;

/**
 * An address found by search or by a map pin.
 *
 * @param label the address as one line
 * @param lat latitude
 * @param lng longitude
 */
public record AddressSuggestion(String label, double lat, double lng) {
}
