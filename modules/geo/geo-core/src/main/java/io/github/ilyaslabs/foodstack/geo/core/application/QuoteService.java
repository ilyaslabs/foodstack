package io.github.ilyaslabs.foodstack.geo.core.application;

import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.function.Supplier;

import io.github.ilyaslabs.foodstack.geo.api.DeliveryAreaRequest;
import io.github.ilyaslabs.foodstack.geo.api.DeliveryAreaView;
import io.github.ilyaslabs.foodstack.geo.api.DeliveryMode;
import io.github.ilyaslabs.foodstack.geo.api.DeliveryQuoteView;
import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import io.github.ilyaslabs.foodstack.geo.api.QuoteRequest;
import io.github.ilyaslabs.foodstack.geo.core.domain.GeoMath;
import io.github.ilyaslabs.moduleswitch.ModuleException;

/**
 * Delivery quotes and delivery areas (PRD SE-7). Self-delivery: the first of the branch's zones
 * that contains the address. Operator delivery: base charge plus the per-km rate on the estimated
 * road distance, up to the maximum distance, times the peak multiplier in peak hours, with the
 * operator's minimum order and free-delivery threshold (PRD DR-1b).
 */
public class QuoteService {

    private static final int AREA_CIRCLE_POINTS = 48;

    private final ZoneService zones;

    private final OperatorPricingService pricing;

    private final double roadFactor;

    private final RoutingService routing;

    private final Clock clock;

    private final Supplier<ZoneId> timeZone;

    public QuoteService(ZoneService zones, OperatorPricingService pricing, double roadFactor) {
        this(zones, pricing, roadFactor, RoutingService.estimating(roadFactor), Clock.systemUTC(), () -> ZoneOffset.UTC);
    }

    /**
     * @param timeZone the deployment's time zone, which peak hours are in
     */
    public QuoteService(ZoneService zones, OperatorPricingService pricing, double roadFactor, RoutingService routing,
            Clock clock, Supplier<ZoneId> timeZone) {
        this.zones = zones;
        this.pricing = pricing;
        this.roadFactor = roadFactor;
        this.routing = routing;
        this.clock = clock;
        this.timeZone = timeZone;
    }

    public DeliveryQuoteView quote(QuoteRequest request) {
        requireValid(request.storeLocation(), "storeLocation");
        requireValid(request.destination(), "destination");
        long distance = this.routing.route(request.storeLocation(), request.destination()).distanceMeters();
        if (request.deliveryMode() == DeliveryMode.OPERATOR) {
            OperatorPricing operator = this.pricing.get();
            if (!operator.enabled()) {
                return refusal(DeliveryQuoteView.OPERATOR_DELIVERY_OFF, distance, DeliveryMode.OPERATOR);
            }
            if (distance > operator.maxDistanceMeters()) {
                return refusal(DeliveryQuoteView.TOO_FAR, distance, DeliveryMode.OPERATOR);
            }
            long charge = operator.baseChargeMinor() + Math.round(operator.perKmMinor() * distance / 1000.0);
            if (operator.peakMultiplierPercent() > 100 && isPeak(operator.peakWindows())) {
                charge = Math.round(charge * operator.peakMultiplierPercent() / 100.0);
            }
            return new DeliveryQuoteView(true, null, charge, operator.minOrderMinor(), operator.freeDeliveryAboveMinor(),
                    distance, null, DeliveryMode.OPERATOR);
        }
        for (ZoneDetails zone : this.zones.getZones(request.storeId())) {
            if (GeoMath.contains(zone.polygon(), request.destination())) {
                return new DeliveryQuoteView(true, null, zone.chargeMinor(), zone.minOrderMinor(),
                        zone.freeDeliveryAboveMinor(), distance, zone.id(), DeliveryMode.SELF);
            }
        }
        return refusal(DeliveryQuoteView.OUT_OF_AREA, distance, DeliveryMode.SELF);
    }

    public DeliveryAreaView deliveryArea(DeliveryAreaRequest request) {
        if (request.deliveryMode() == DeliveryMode.OPERATOR) {
            OperatorPricing operator = this.pricing.get();
            if (!operator.enabled() || request.storeLocation() == null || !request.storeLocation().isValid()) {
                return new DeliveryAreaView(request.storeId(), List.of());
            }
            double straightLine = operator.maxDistanceMeters() / this.roadFactor;
            return new DeliveryAreaView(request.storeId(),
                    List.of(GeoMath.circle(request.storeLocation(), straightLine, AREA_CIRCLE_POINTS)));
        }
        return new DeliveryAreaView(request.storeId(),
                this.zones.getZones(request.storeId()).stream().map(ZoneDetails::polygon).toList());
    }

    private boolean isPeak(List<PeakWindow> windows) {
        if (windows.isEmpty()) {
            return false;
        }
        ZonedDateTime now = this.clock.instant().atZone(this.timeZone.get());
        LocalTime time = now.toLocalTime();
        return windows.stream().anyMatch((w) -> w.contains(now.getDayOfWeek(), time));
    }

    private static DeliveryQuoteView refusal(String reason, long distance, DeliveryMode mode) {
        return new DeliveryQuoteView(false, reason, 0, 0, null, distance, null, mode);
    }

    private static void requireValid(GeoPoint point, String name) {
        if (point == null || !point.isValid()) {
            throw new ModuleException.BadRequest("The " + name + " is not a valid point", "LOCATION_INVALID");
        }
    }
}
