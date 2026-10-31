package io.github.ilyaslabs.foodstack.geo.core.geocoding;

import java.util.List;

import io.github.ilyaslabs.foodstack.geo.core.application.AddressSuggestion;

/** {@code foodstack.geo.geocoding.provider=none}: customers use their device location instead. */
public class DisabledGeocodingProvider implements GeocodingProvider {

    @Override
    public List<AddressSuggestion> search(String query, String language) {
        return List.of();
    }

    @Override
    public List<AddressSuggestion> reverse(double lat, double lng, String language) {
        return List.of();
    }

    @Override
    public boolean isAvailable() {
        return false;
    }
}
