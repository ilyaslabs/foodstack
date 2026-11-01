package io.github.ilyaslabs.foodstack.geo.core.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import io.github.ilyaslabs.foodstack.geo.api.events.ZoneChanged;
import io.github.ilyaslabs.foodstack.geo.core.domain.GeoMath;
import io.github.ilyaslabs.foodstack.geo.core.persistence.ZoneDocument;
import io.github.ilyaslabs.foodstack.geo.core.persistence.ZoneRepository;
import io.github.ilyaslabs.foodstack.platform.core.Ids;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.moduleswitch.ModuleException;

import org.springframework.data.geo.Point;
import org.springframework.data.mongodb.core.geo.GeoJsonPolygon;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delivery zones of self-delivery branches (PRD ST-11). A branch's zones are saved as a whole;
 * when zones overlap, the first one in the list that contains the address wins, so rings such as
 * 0-3 km and 0-6 km work as expected.
 */
public class ZoneService {

    public static final int MAX_ZONES = 20;

    /** A ring sent by centre and radius becomes this many corners, few enough to reshape by hand on the map. */
    static final int CIRCLE_POINTS = 16;

    private final ZoneRepository zones;

    private final OutboxWriter outbox;

    private final TransactionTemplate transaction;

    private final Clock clock;

    public ZoneService(ZoneRepository zones, OutboxWriter outbox, TransactionTemplate transaction, Clock clock) {
        this.zones = zones;
        this.outbox = outbox;
        this.transaction = transaction;
        this.clock = clock;
    }

    public List<ZoneDetails> getZones(String storeId) {
        return this.zones.findByStoreIdOrderBySortOrderAsc(storeId).stream().map(ZoneService::toDetails).toList();
    }

    /** Replaces every zone of the branch and publishes {@link ZoneChanged}. */
    public List<ZoneDetails> replaceZones(String storeId, List<ZoneInput> inputs, String userId) {
        validate(storeId, inputs);
        Instant now = this.clock.instant();
        List<ZoneDocument> documents = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            documents.add(toDocument(storeId, i, inputs.get(i), now, userId));
        }
        List<ZoneDocument> saved = this.transaction.execute((status) -> {
            this.zones.deleteByStoreId(storeId);
            List<ZoneDocument> result = this.zones.saveAll(documents);
            this.outbox.append(new ZoneChanged(storeId, result.size(), userId));
            return result;
        });
        return saved.stream().map(ZoneService::toDetails).toList();
    }

    private static ZoneDocument toDocument(String storeId, int order, ZoneInput input, Instant now, String userId) {
        ZoneDocument document = new ZoneDocument();
        document.setId(Ids.newId());
        document.setStoreId(storeId);
        document.setName(input.name().trim());
        document.setSortOrder(order);
        List<GeoPoint> outline;
        if (input.radiusMeters() != null) {
            document.setCenterLat(input.center().lat());
            document.setCenterLng(input.center().lng());
            document.setRadiusMeters(input.radiusMeters());
            outline = GeoMath.circle(input.center(), input.radiusMeters(), CIRCLE_POINTS);
        }
        else {
            outline = input.polygon();
        }
        document.setArea(toGeoJson(outline));
        document.setChargeMinor(input.chargeMinor());
        document.setMinOrderMinor(input.minOrderMinor());
        document.setFreeDeliveryAboveMinor(input.freeDeliveryAboveMinor());
        document.setUpdatedAt(now);
        document.setUpdatedBy(userId);
        return document;
    }

    static GeoJsonPolygon toGeoJson(List<GeoPoint> outline) {
        List<Point> points = new ArrayList<>(outline.size() + 1);
        outline.forEach((point) -> points.add(new Point(point.lng(), point.lat())));
        points.add(points.getFirst());
        return new GeoJsonPolygon(points);
    }

    static List<GeoPoint> outline(GeoJsonPolygon polygon) {
        List<Point> points = polygon.getCoordinates().getFirst().getCoordinates();
        List<GeoPoint> outline = new ArrayList<>(points.size());
        for (int i = 0; i < points.size() - 1; i++) {
            outline.add(new GeoPoint(points.get(i).getY(), points.get(i).getX()));
        }
        return outline;
    }

    static ZoneDetails toDetails(ZoneDocument document) {
        GeoPoint center = (document.getCenterLat() != null)
                ? new GeoPoint(document.getCenterLat(), document.getCenterLng()) : null;
        return new ZoneDetails(document.getId(), document.getName(), center, document.getRadiusMeters(),
                outline(document.getArea()), document.getChargeMinor(), document.getMinOrderMinor(),
                document.getFreeDeliveryAboveMinor(), document.getUpdatedAt());
    }

    private static void validate(String storeId, List<ZoneInput> inputs) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (storeId == null || storeId.isBlank()) {
            errors.put("storeId", "is required");
        }
        if (inputs == null) {
            errors.put("zones", "are required");
        }
        else if (inputs.size() > MAX_ZONES) {
            errors.put("zones", "at most " + MAX_ZONES + " zones per branch");
        }
        else {
            for (int i = 0; i < inputs.size(); i++) {
                validate("zones[" + i + "].", inputs.get(i), errors);
            }
        }
        if (!errors.isEmpty()) {
            throw new ModuleException.BadRequest("The delivery zones are invalid", "ZONES_INVALID", Map.of("errors", errors));
        }
    }

    private static void validate(String prefix, ZoneInput zone, Map<String, String> errors) {
        if (zone.name() == null || zone.name().isBlank() || zone.name().trim().length() > 60) {
            errors.put(prefix + "name", "must be 1 to 60 characters");
        }
        if (zone.radiusMeters() != null) {
            if (zone.center() == null || !zone.center().isValid()) {
                errors.put(prefix + "center", "must be a valid point");
            }
            if (zone.radiusMeters() < 200 || zone.radiusMeters() > 50_000) {
                errors.put(prefix + "radiusMeters", "must be 200 m to 50 km");
            }
        }
        else if (zone.polygon() == null || zone.polygon().size() < 3 || zone.polygon().size() > 200
                || !zone.polygon().stream().allMatch((point) -> point != null && point.isValid())) {
            errors.put(prefix + "polygon", "needs 3 to 200 valid points, or give a centre and radius");
        }
        else if (selfIntersects(zone.polygon())) {
            errors.put(prefix + "polygon", "the outline crosses itself; move the corners so its edges do not cross");
        }
        if (zone.chargeMinor() < 0) {
            errors.put(prefix + "chargeMinor", "must not be negative");
        }
        if (zone.minOrderMinor() < 0) {
            errors.put(prefix + "minOrderMinor", "must not be negative");
        }
        if (zone.freeDeliveryAboveMinor() != null && zone.freeDeliveryAboveMinor() < 0) {
            errors.put(prefix + "freeDeliveryAboveMinor", "must not be negative");
        }
    }

    /** True when two edges that are not neighbours touch or cross; the quote's point-in-zone test needs a simple outline. */
    static boolean selfIntersects(List<GeoPoint> polygon) {
        int n = polygon.size();
        for (int i = 0; i < n; i++) {
            GeoPoint a = polygon.get(i);
            GeoPoint b = polygon.get((i + 1) % n);
            for (int j = i + 1; j < n; j++) {
                if (j == i + 1 || (i == 0 && j == n - 1)) {
                    continue;
                }
                if (segmentsTouch(a, b, polygon.get(j), polygon.get((j + 1) % n))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean segmentsTouch(GeoPoint p1, GeoPoint p2, GeoPoint q1, GeoPoint q2) {
        double d1 = cross(q1, q2, p1);
        double d2 = cross(q1, q2, p2);
        double d3 = cross(p1, p2, q1);
        double d4 = cross(p1, p2, q2);
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) {
            return true;
        }
        return (d1 == 0 && onSegment(q1, q2, p1)) || (d2 == 0 && onSegment(q1, q2, p2))
                || (d3 == 0 && onSegment(p1, p2, q1)) || (d4 == 0 && onSegment(p1, p2, q2));
    }

    private static double cross(GeoPoint a, GeoPoint b, GeoPoint c) {
        return (b.lng() - a.lng()) * (c.lat() - a.lat()) - (b.lat() - a.lat()) * (c.lng() - a.lng());
    }

    private static boolean onSegment(GeoPoint a, GeoPoint b, GeoPoint c) {
        return Math.min(a.lng(), b.lng()) <= c.lng() && c.lng() <= Math.max(a.lng(), b.lng())
                && Math.min(a.lat(), b.lat()) <= c.lat() && c.lat() <= Math.max(a.lat(), b.lat());
    }
}
