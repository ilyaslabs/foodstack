package io.github.ilyaslabs.foodstack.geo.api;

/**
 * A point on the map, WGS 84.
 *
 * @param lat latitude, -90 to 90
 * @param lng longitude, -180 to 180
 */
public record GeoPoint(double lat, double lng) {

    public boolean isValid() {
        return Double.isFinite(this.lat) && Double.isFinite(this.lng) && this.lat >= -90 && this.lat <= 90
                && this.lng >= -180 && this.lng <= 180;
    }
}
