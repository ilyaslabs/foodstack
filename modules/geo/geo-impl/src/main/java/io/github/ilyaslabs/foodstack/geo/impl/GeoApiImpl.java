package io.github.ilyaslabs.foodstack.geo.impl;

import io.github.ilyaslabs.foodstack.geo.api.DeliveryAreaRequest;
import io.github.ilyaslabs.foodstack.geo.api.DeliveryAreaView;
import io.github.ilyaslabs.foodstack.geo.api.DeliveryQuoteView;
import io.github.ilyaslabs.foodstack.geo.api.GeoApi;
import io.github.ilyaslabs.foodstack.geo.api.QuoteRequest;
import io.github.ilyaslabs.foodstack.geo.core.application.QuoteService;

import org.springframework.stereotype.Component;

/** {@link GeoApi} for other modules: delegates to the core quote service. */
@Component
class GeoApiImpl implements GeoApi {

    private final QuoteService quoteService;

    GeoApiImpl(QuoteService quoteService) {
        this.quoteService = quoteService;
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
