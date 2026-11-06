package io.github.ilyaslabs.foodstack.geo.impl;

import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.geo.api.CityView;
import io.github.ilyaslabs.foodstack.geo.api.DeliveryAreaRequest;
import io.github.ilyaslabs.foodstack.geo.api.DeliveryAreaView;
import io.github.ilyaslabs.foodstack.geo.api.DeliveryMode;
import io.github.ilyaslabs.foodstack.geo.api.DeliveryQuoteView;
import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import io.github.ilyaslabs.foodstack.geo.api.QuoteRequest;
import io.github.ilyaslabs.foodstack.geo.api.RouteRequest;
import io.github.ilyaslabs.foodstack.geo.core.application.City;
import io.github.ilyaslabs.foodstack.geo.core.application.CityPricing;
import io.github.ilyaslabs.foodstack.geo.core.application.CityService;
import io.github.ilyaslabs.foodstack.geo.core.application.QuoteService;
import io.github.ilyaslabs.foodstack.geo.core.application.RoutingService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class GeoApiImplTest {

    private final QuoteService service = mock(QuoteService.class);

    private final RoutingService routing = RoutingService.estimating(1.3);

    private final CityService cities = mock(CityService.class);

    private final GeoApiImpl api = new GeoApiImpl(this.service, this.routing, this.cities);

    @Test
    @DisplayName("delegates quotes and delivery areas to the core service")
    void delegates() {
        QuoteRequest request = new QuoteRequest("s1", new GeoPoint(1, 2), DeliveryMode.SELF, new GeoPoint(1.01, 2));
        DeliveryQuoteView quote = new DeliveryQuoteView(true, null, 100, 0, null, 1200, "z1", DeliveryMode.SELF);
        DeliveryAreaRequest areaRequest = new DeliveryAreaRequest("s1", new GeoPoint(1, 2), DeliveryMode.SELF);
        DeliveryAreaView area = new DeliveryAreaView("s1", List.of());
        given(this.service.quote(request)).willReturn(quote);
        given(this.service.deliveryArea(areaRequest)).willReturn(area);

        assertThat(this.api.quote(request)).isEqualTo(quote);
        assertThat(this.api.deliveryArea(areaRequest)).isEqualTo(area);
        assertThat(this.api.route(new RouteRequest(new GeoPoint(1, 2), new GeoPoint(1.01, 2))).estimated()).isTrue();
    }

    @Test
    @DisplayName("lists cities and finds the nearest, saying whether each has its own pricing")
    void cities() {
        City lahore = new City("c1", Map.of("en", "Lahore"), new GeoPoint(31.52, 74.35), true,
                new CityPricing(100, 0, 8000, 0, null, 100, List.of()), 1, null);
        given(this.cities.list()).willReturn(List.of(lahore));
        given(this.cities.nearest(new GeoPoint(31.5, 74.3))).willReturn(lahore);

        assertThat(this.api.cities()).containsExactly(
                new CityView("c1", Map.of("en", "Lahore"), new GeoPoint(31.52, 74.35), true, true));
        assertThat(this.api.nearestCity(new GeoPoint(31.5, 74.3)).id()).isEqualTo("c1");
        assertThat(this.api.nearestCity(new GeoPoint(0, 0))).isNull();
    }
}
