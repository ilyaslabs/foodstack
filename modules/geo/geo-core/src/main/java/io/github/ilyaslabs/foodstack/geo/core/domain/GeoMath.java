package io.github.ilyaslabs.foodstack.geo.core.domain;

import java.util.ArrayList;
import java.util.List;

import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;

/** Spherical helpers, accurate enough for city-sized delivery areas. */
public final class GeoMath {

    private static final double EARTH_RADIUS_METERS = 6_371_008.8;

    private GeoMath() {
    }

    /** Great-circle distance in metres. */
    public static double distanceMeters(GeoPoint a, GeoPoint b) {
        double lat1 = Math.toRadians(a.lat());
        double lat2 = Math.toRadians(b.lat());
        double dLat = lat2 - lat1;
        double dLng = Math.toRadians(b.lng() - a.lng());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    /** A circle as a polygon of {@code points} vertices, counter-clockwise, first point not repeated. */
    public static List<GeoPoint> circle(GeoPoint center, double radiusMeters, int points) {
        List<GeoPoint> ring = new ArrayList<>(points);
        double angular = radiusMeters / EARTH_RADIUS_METERS;
        double lat = Math.toRadians(center.lat());
        double lng = Math.toRadians(center.lng());
        for (int i = 0; i < points; i++) {
            double bearing = 2 * Math.PI * i / points;
            double lat2 = Math.asin(Math.sin(lat) * Math.cos(angular) + Math.cos(lat) * Math.sin(angular) * Math.cos(bearing));
            double lng2 = lng + Math.atan2(Math.sin(bearing) * Math.sin(angular) * Math.cos(lat),
                    Math.cos(angular) - Math.sin(lat) * Math.sin(lat2));
            ring.add(new GeoPoint(round(Math.toDegrees(lat2)), round(normalizeLng(Math.toDegrees(lng2)))));
        }
        return ring;
    }

    /** Ray casting on longitude and latitude; fine for polygons that do not cross the antimeridian. */
    public static boolean contains(List<GeoPoint> polygon, GeoPoint point) {
        boolean inside = false;
        for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
            GeoPoint a = polygon.get(i);
            GeoPoint b = polygon.get(j);
            boolean crosses = (a.lat() > point.lat()) != (b.lat() > point.lat());
            if (crosses && point.lng() < (b.lng() - a.lng()) * (point.lat() - a.lat()) / (b.lat() - a.lat()) + a.lng()) {
                inside = !inside;
            }
        }
        return inside;
    }

    private static double normalizeLng(double lng) {
        return ((lng + 540) % 360) - 180;
    }

    private static double round(double value) {
        return Math.round(value * 1e6) / 1e6;
    }
}
