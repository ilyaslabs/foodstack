package io.github.ilyaslabs.foodstack.geo.core.application;

import java.util.List;

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
 * road distance, up to the maximum distance.
 */
public class QuoteService {

    private static final int AREA_CIRCLE_POINTS = 48;

    private final ZoneService zones;

    private final OperatorPricingService pricing;

    private final double roadFactor;

    public QuoteService(ZoneService zones, OperatorPricingService pricing, double roadFactor) {
        this.zones = zones;
        this.pricing = pricing;
        this.roadFactor = roadFactor;
    }

    public DeliveryQuoteView quote(QuoteRequest request) {
        requireValid(request.storeLocation(), "storeLocation");
        requireValid(request.destination(), "destination");
        long distance = Math.round(GeoMath.distanceMeters(request.storeLocation(), request.destination()) * this.roadFactor);
        if (request.deliveryMode() == DeliveryMode.OPERATOR) {
            OperatorPricing operator = this.pricing.get();
            if (!operator.enabled()) {
                return refusal(DeliveryQuoteView.OPERATOR_DELIVERY_OFF, distance, DeliveryMode.OPERATOR);
            }
            if (distance > operator.maxDistanceMeters()) {
                return refusal(DeliveryQuoteView.TOO_FAR, distance, DeliveryMode.OPERATOR);
            }
            long charge = operator.baseChargeMinor() + Math.round(operator.perKmMinor() * distance / 1000.0);
            return new DeliveryQuoteView(true, null, charge, 0, null, distance, null, DeliveryMode.OPERATOR);
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

    private static DeliveryQuoteView refusal(String reason, long distance, DeliveryMode mode) {
        return new DeliveryQuoteView(false, reason, 0, 0, null, distance, null, mode);
    }

    private static void requireValid(GeoPoint point, String name) {
        if (point == null || !point.isValid()) {
            throw new ModuleException.BadRequest("The " + name + " is not a valid point", "LOCATION_INVALID");
        }
    }
}
