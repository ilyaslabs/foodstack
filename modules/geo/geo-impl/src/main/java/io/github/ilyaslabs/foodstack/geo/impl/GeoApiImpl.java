package io.github.ilyaslabs.foodstack.geo.impl;

import io.github.ilyaslabs.foodstack.geo.api.DeliveryAreaRequest;
import io.github.ilyaslabs.foodstack.geo.api.DeliveryAreaView;
import io.github.ilyaslabs.foodstack.geo.api.DeliveryQuoteView;
import io.github.ilyaslabs.foodstack.geo.api.GeoApi;
import io.github.ilyaslabs.foodstack.geo.api.QuoteRequest;
import io.github.ilyaslabs.foodstack.geo.api.RouteRequest;
import io.github.ilyaslabs.foodstack.geo.api.RouteView;
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

    GeoApiImpl(QuoteService quoteService, RoutingService routingService) {
        this.quoteService = quoteService;
        this.routingService = routingService;
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
