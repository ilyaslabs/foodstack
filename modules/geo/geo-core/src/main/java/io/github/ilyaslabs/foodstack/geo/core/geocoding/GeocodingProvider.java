package io.github.ilyaslabs.foodstack.geo.core.geocoding;

import java.util.List;

import io.github.ilyaslabs.foodstack.geo.core.application.AddressSuggestion;

/** Maps provider SPI for addresses (TDD 12). Nominatim (OpenStreetMap) is the default adapter. */
public interface GeocodingProvider {

    /** Addresses matching free text, best first, at most five. */
    List<AddressSuggestion> search(String query, String language);

    /** The address at a map pin; empty when there is none. */
    List<AddressSuggestion> reverse(double lat, double lng, String language);

    default boolean isAvailable() {
        return true;
    }
}
