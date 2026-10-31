package io.github.ilyaslabs.foodstack.geo.core.application;

import java.util.List;

import io.github.ilyaslabs.foodstack.geo.api.DeliveryAreaRequest;
import io.github.ilyaslabs.foodstack.geo.api.DeliveryMode;
import io.github.ilyaslabs.foodstack.geo.api.DeliveryQuoteView;
import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import io.github.ilyaslabs.foodstack.geo.api.QuoteRequest;
import io.github.ilyaslabs.foodstack.geo.core.domain.GeoMath;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class QuoteServiceTest {

    private static final GeoPoint STORE = new GeoPoint(31.5204, 74.3587);

    /** About 1.1 km north of the store. */
    private static final GeoPoint NEAR = new GeoPoint(31.5304, 74.3587);

    /** About 4.4 km north. */
    private static final GeoPoint MIDDLE = new GeoPoint(31.5604, 74.3587);

    /** About 11 km north. */
    private static final GeoPoint FAR = new GeoPoint(31.6204, 74.3587);

    private final ZoneService zones = mock(ZoneService.class);

    private final OperatorPricingService pricing = mock(OperatorPricingService.class);

    private final QuoteService service = new QuoteService(this.zones, this.pricing, 1.3);

    @Test
    @DisplayName("prices self-delivery with the first zone that contains the address")
    void selfDeliveryFirstMatch() {
        // given rings 0-3 km and 0-6 km
        given(this.zones.getZones("s1")).willReturn(List.of(ring("z1", 3000, 10_000), ring("z2", 6000, 20_000)));

        // when
        DeliveryQuoteView near = this.service.quote(new QuoteRequest("s1", STORE, DeliveryMode.SELF, NEAR));
        DeliveryQuoteView middle = this.service.quote(new QuoteRequest("s1", STORE, DeliveryMode.SELF, MIDDLE));
        DeliveryQuoteView far = this.service.quote(new QuoteRequest("s1", STORE, DeliveryMode.SELF, FAR));

        // then
        assertThat(near.deliverable()).isTrue();
        assertThat(near.zoneId()).isEqualTo("z1");
        assertThat(near.chargeMinor()).isEqualTo(10_000);
        assertThat(near.minOrderMinor()).isEqualTo(50_000);
        assertThat(middle.zoneId()).isEqualTo("z2");
        assertThat(middle.chargeMinor()).isEqualTo(20_000);
        assertThat(far.deliverable()).isFalse();
        assertThat(far.reason()).isEqualTo(DeliveryQuoteView.OUT_OF_AREA);
    }

    @Test
    @DisplayName("prices operator delivery as base plus per km of estimated road distance")
    void operatorDelivery() {
        given(this.pricing.get()).willReturn(new OperatorPricing(true, 10_000, 3_000, 8000, 1, null));

        DeliveryQuoteView quote = this.service.quote(new QuoteRequest("s1", STORE, DeliveryMode.OPERATOR, MIDDLE));

        long road = Math.round(GeoMath.distanceMeters(STORE, MIDDLE) * 1.3);
        assertThat(quote.deliverable()).isTrue();
        assertThat(quote.distanceMeters()).isEqualTo(road);
        assertThat(quote.chargeMinor()).isEqualTo(10_000 + Math.round(3_000 * road / 1000.0));
    }

    @Test
    @DisplayName("refuses operator delivery beyond the maximum distance or while it is off")
    void operatorRefusals() {
        given(this.pricing.get()).willReturn(new OperatorPricing(true, 10_000, 3_000, 8000, 1, null));
        assertThat(this.service.quote(new QuoteRequest("s1", STORE, DeliveryMode.OPERATOR, FAR)).reason())
            .isEqualTo(DeliveryQuoteView.TOO_FAR);

        given(this.pricing.get()).willReturn(new OperatorPricing(false, 10_000, 3_000, 8000, 1, null));
        assertThat(this.service.quote(new QuoteRequest("s1", STORE, DeliveryMode.OPERATOR, NEAR)).reason())
            .isEqualTo(DeliveryQuoteView.OPERATOR_DELIVERY_OFF);
    }

    @Test
    @DisplayName("gives the zone polygons, or the operator circle, as the delivery area")
    void deliveryArea() {
        given(this.zones.getZones("s1")).willReturn(List.of(ring("z1", 3000, 0)));
        given(this.pricing.get()).willReturn(new OperatorPricing(true, 0, 0, 13_000, 1, null));

        assertThat(this.service.deliveryArea(new DeliveryAreaRequest("s1", STORE, DeliveryMode.SELF)).polygons()).hasSize(1);
        List<GeoPoint> circle = this.service.deliveryArea(new DeliveryAreaRequest("s1", STORE, DeliveryMode.OPERATOR))
            .polygons().getFirst();
        assertThat(GeoMath.distanceMeters(STORE, circle.getFirst())).isBetween(9_900.0, 10_100.0);
    }

    @Test
    @DisplayName("rejects invalid locations")
    void invalidLocation() {
        assertThatThrownBy(() -> this.service.quote(new QuoteRequest("s1", STORE, DeliveryMode.SELF, new GeoPoint(120, 0))))
            .isInstanceOfSatisfying(ModuleException.BadRequest.class, (ex) -> assertThat(ex.code()).isEqualTo("LOCATION_INVALID"));
    }

    private static ZoneDetails ring(String id, int radius, long charge) {
        return new ZoneDetails(id, id, STORE, radius, GeoMath.circle(STORE, radius, 48), charge, 50_000, null, null);
    }
}
