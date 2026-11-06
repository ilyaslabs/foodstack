package io.github.ilyaslabs.foodstack.geo.api;

import io.github.ilyaslabs.moduleswitch.Param;

/**
 * What other modules may ask the geo module. Both calls are cheap (a branch has a handful of
 * zones) and never call a maps provider.
 */
public interface GeoApi {

    /**
     * What delivering from a branch to an address costs, or why it is not possible. Self-delivery
     * branches use their own zones (PRD ST-11); operator delivery uses the operator's pricing (SE-7).
     */
    DeliveryQuoteView quote(@Param("request") QuoteRequest request);

    /**
     * The area a branch delivers to, for the search index: its zone polygons for self-delivery, or
     * a circle of the operator's maximum distance for operator delivery. Empty when it delivers nowhere.
     */
    DeliveryAreaView deliveryArea(@Param("request") DeliveryAreaRequest request);

    /**
     * Road distance and driving time between two points, from the routing provider (OSRM) or, when
     * it is off or not answering, estimated from the straight line. Answers are cached briefly.
     */
    RouteView route(@Param("request") RouteRequest request);

    /** Every city, active or not, sorted by name in the default language. Cheap: read from the cache. */
    java.util.List<CityView> cities();

    /**
     * The active city whose centre is nearest to a point, within {@code foodstack.geo.city-radius-meters}
     * (60 km by default), to suggest a new branch's or rider's city; {@code null} when there is none.
     */
    CityView nearestCity(@Param("point") GeoPoint point);
}
