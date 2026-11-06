package io.github.ilyaslabs.foodstack.geo.impl;

import java.util.List;

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

    @Override
    public List<CityView> cities() {
        return this.cityService.list().stream().map(GeoApiImpl::view).toList();
    }

    @Override
    public CityView nearestCity(GeoPoint point) {
        City city = this.cityService.nearest(point);
        return (city != null) ? view(city) : null;
    }

    private static CityView view(City city) {
        return new CityView(city.id(), city.name(), city.centre(), city.active(), city.pricing() != null);
    }

    @Override
    public RouteView route(RouteRequest request) {
        return this.routingService.route(request.from(), request.to());
    }

    @Override
    public DeliveryQuoteView quote(QuoteRequest request) {
        return this.quoteService.quote(request);
    }

    @Override
    public DeliveryAreaView deliveryArea(DeliveryAreaRequest request) {
        return this.quoteService.deliveryArea(request);
    }
}
