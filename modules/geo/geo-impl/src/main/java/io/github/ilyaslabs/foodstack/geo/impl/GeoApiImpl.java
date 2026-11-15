package io.github.ilyaslabs.foodstack.geo.impl;

import java.util.List;

import io.github.ilyaslabs.evl.EVL;
import io.github.ilyaslabs.evl.Flow;
import io.github.ilyaslabs.foodstack.geo.api.CityView;
import io.github.ilyaslabs.foodstack.geo.api.DeliveryAreaRequest;
import io.github.ilyaslabs.foodstack.geo.api.DeliveryAreaView;
import io.github.ilyaslabs.foodstack.geo.api.DeliveryQuoteView;
import io.github.ilyaslabs.foodstack.geo.api.GeoApi;
import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import io.github.ilyaslabs.foodstack.geo.api.QuoteRequest;
import io.github.ilyaslabs.foodstack.geo.api.RouteRequest;
import io.github.ilyaslabs.foodstack.geo.api.RouteView;
import io.github.ilyaslabs.foodstack.geo.core.application.City;
import io.github.ilyaslabs.foodstack.geo.core.application.CityService;
import io.github.ilyaslabs.foodstack.geo.core.application.QuoteService;
import io.github.ilyaslabs.foodstack.geo.core.application.RoutingService;

import org.springframework.stereotype.Component;

/**
 * {@link GeoApi} for other modules: delegates to the core quote service. Open to every caller,
 * signed in or not: quotes and delivery areas are what any customer sees (TDD 9.1).
 */
@Component
class GeoApiImpl implements GeoApi {

    private final QuoteService quoteService;

    private final RoutingService routingService;

    private final CityService cityService;

    GeoApiImpl(QuoteService quoteService, RoutingService routingService, CityService cityService) {
        this.quoteService = quoteService;
        this.routingService = routingService;
        this.cityService = cityService;
    }

    @Flow(value = "geo-api-cities", label = "Cities for other modules", description = "")
    @EVL.Endpoint(method = "API", path = "GeoApi.cities", caller = "Store, order")
    @Override
    public List<CityView> cities() {
        return this.cityService.list().stream().map(GeoApiImpl::view).toList();
    }

    @Flow(value = "geo-api-nearest-city", label = "Nearest city for other modules", description = "Gives a new branch its city")
    @EVL.Endpoint(method = "API", path = "GeoApi.nearestCity", caller = "Store")
    @Override
    public CityView nearestCity(GeoPoint point) {
        City city = this.cityService.nearest(point);
        return (city != null) ? view(city) : null;
    }

    private static CityView view(City city) {
        return new CityView(city.id(), city.name(), city.centre(), city.active(), city.pricing() != null);
    }

    @Flow(value = "geo-api-route", label = "Road distance and time", description = "Used by dispatch and live ETAs")
    @EVL.Endpoint(method = "API", path = "GeoApi.route", caller = "Dispatch, order")
    @Override
    public RouteView route(RouteRequest request) {
        return this.routingService.route(request.from(), request.to());
    }

    @Flow(value = "geo-api-quote", label = "Quote delivery", description = "Store zones for self-delivery, operator or city pricing otherwise")
    @EVL.Endpoint(method = "API", path = "GeoApi.quote", caller = "Cart, search")
    @Override
    public DeliveryQuoteView quote(QuoteRequest request) {
        return this.quoteService.quote(request);
    }

    @Flow(value = "geo-api-delivery-area", label = "Delivery area outline", description = "Zones, or a circle for operator delivery")
    @EVL.Endpoint(method = "API", path = "GeoApi.deliveryArea", caller = "Store, search")
    @Override
    public DeliveryAreaView deliveryArea(DeliveryAreaRequest request) {
        return this.quoteService.deliveryArea(request);
    }
}
