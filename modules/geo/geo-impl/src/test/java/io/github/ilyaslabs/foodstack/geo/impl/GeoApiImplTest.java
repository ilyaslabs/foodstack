package io.github.ilyaslabs.foodstack.geo.impl;

import java.util.List;

import io.github.ilyaslabs.foodstack.geo.api.DeliveryAreaRequest;
import io.github.ilyaslabs.foodstack.geo.api.DeliveryAreaView;
import io.github.ilyaslabs.foodstack.geo.api.DeliveryMode;
import io.github.ilyaslabs.foodstack.geo.api.DeliveryQuoteView;
import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import io.github.ilyaslabs.foodstack.geo.api.QuoteRequest;
import io.github.ilyaslabs.foodstack.geo.api.RouteRequest;
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

    private final GeoApiImpl api = new GeoApiImpl(this.service, this.routing);

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
}
