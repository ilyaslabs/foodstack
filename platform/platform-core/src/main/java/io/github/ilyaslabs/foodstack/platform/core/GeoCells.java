package io.github.ilyaslabs.foodstack.platform.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A fixed grid of map cells, a quarter of a degree on each side (about 28 km north to south), used
 * to split country-wide live data by place (TDD 7.5): online riders are kept in one Redis GEO set
 * per cell, and live map messages go to one channel per cell, so no single key or channel carries
 * a whole country. A map follows the cells it shows; a rider search reads the cells its circle
 * touches. The grid never changes.
 */
public final class GeoCells {

    /** Cell side in degrees. */
    public static final double SIZE_DEGREES = 0.25;

    /** The most cells one query may cover; a wider view must zoom in. */
    public static final int MAX_CELLS = 64;

    private static final double METERS_PER_DEGREE_LAT = 111_320;

    private GeoCells() {
    }

    /** The cell holding a point, such as {@code 126_297}. */
    public static String of(double lat, double lng) {
        return id(row(lat), column(lng));
    }

    /** Cells touching a circle, at most {@link #MAX_CELLS}. */
    public static List<String> around(double lat, double lng, double radiusMeters) {
        double dLat = radiusMeters / METERS_PER_DEGREE_LAT;
        double cos = Math.max(0.01, Math.cos(Math.toRadians(lat)));
        double dLng = radiusMeters / (METERS_PER_DEGREE_LAT * cos);
        return within(lat - dLat, lng - dLng, lat + dLat, lng + dLng);
    }

    /**
     * Cells touching a box.
     * @throws IllegalArgumentException when the box covers more than {@link #MAX_CELLS} cells
     */
    public static List<String> within(double south, double west, double north, double east) {
        int fromRow = row(Math.max(-90, Math.min(south, north)));
        int toRow = row(Math.min(90, Math.max(south, north)));
        int fromColumn = column(Math.max(-180, Math.min(west, east)));
        int toColumn = column(Math.min(180, Math.max(west, east)));
        long count = (long) (toRow - fromRow + 1) * (toColumn - fromColumn + 1);
        if (count > MAX_CELLS) {
            throw new IllegalArgumentException("The area covers " + count + " cells; at most " + MAX_CELLS);
        }
        List<String> cells = new ArrayList<>((int) count);
        for (int r = fromRow; r <= toRow; r++) {
            for (int c = fromColumn; c <= toColumn; c++) {
                cells.add(id(r, c));
            }
        }
        return cells;
    }

    private static int row(double lat) {
        return (int) Math.floor((lat + 90) / SIZE_DEGREES);
    }

    private static int column(double lng) {
        return (int) Math.floor((lng + 180) / SIZE_DEGREES);
    }

    private static String id(int row, int column) {
        return String.format(Locale.ROOT, "%d_%d", row, column);
    }
}
