package io.github.ilyaslabs.foodstack.cart.core.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.cart.api.CheckoutRequest;
import io.github.ilyaslabs.foodstack.cart.api.CheckoutView;
import io.github.ilyaslabs.foodstack.cart.core.persistence.CartDocument.CartLine;
import io.github.ilyaslabs.foodstack.cart.core.persistence.CartDocument;
import io.github.ilyaslabs.foodstack.geo.api.DeliveryMode;
import io.github.ilyaslabs.foodstack.geo.api.DeliveryQuoteView;
import io.github.ilyaslabs.foodstack.geo.api.GeoApi;
import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import io.github.ilyaslabs.foodstack.platform.core.Fulfilment;
import io.github.ilyaslabs.foodstack.platform.core.PaymentMethod;
import io.github.ilyaslabs.foodstack.promotion.api.CouponEvaluation;
import io.github.ilyaslabs.foodstack.promotion.api.CouponRequest;
import io.github.ilyaslabs.foodstack.promotion.api.PromotionApi;
import io.github.ilyaslabs.foodstack.settings.api.OrderingView;
import io.github.ilyaslabs.foodstack.settings.api.SettingsApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class CheckoutServiceTest {

    private static final GeoPoint HOME = new GeoPoint(31.50, 74.33);

    private final CartService carts = mock(CartService.class);

    private final GeoApi geo = mock(GeoApi.class);

    private final PromotionApi promotions = mock(PromotionApi.class);

    private final SettingsApi settings = mock(SettingsApi.class);

    private final CheckoutService service = new CheckoutService(this.carts, this.geo, this.promotions, this.settings);

    private final CartDocument cart = new CartDocument("c1", "s1", List.of(new CartLine("l1", "i1", 1, List.of(), null),
            new CartLine("l2", "i2", 1, List.of(), null)), Instant.now(), 7L);

    @BeforeEach
    void setUp() {
        // 2.5% + 20.00 service fee, 16% tax, tips on, cash and card
        given(this.settings.getOrdering()).willReturn(new OrderingView(true, true, 2_000, 250, 1600, true, 180, 1));
        given(this.carts.find("c1")).willReturn(this.cart);
        given(this.carts.price(this.cart)).willReturn(CartFixtures.priced("s1", 60_000, 40_000));
        given(this.geo.quote(any())).willReturn(new DeliveryQuoteView(true, null, 15_000, 50_000, 300_000L, 5_000, "z1",
                DeliveryMode.SELF));
    }

    @Test
    @DisplayName("adds delivery, coupon, service fee, tax and tip into one total (PRD CO-2)")
    void totals() {
        given(this.promotions.evaluate(any())).willReturn(new CouponEvaluation(true, null, Map.of(), "p1", "WELCOME",
                Map.of("en", "20% off"), 20_000, 0, 20_000));

        CheckoutView view = this.service.checkout(new CheckoutRequest("c1", Fulfilment.DELIVERY, HOME, " welcome ", 5_000,
                PaymentMethod.CARD));

        assertThat(view.problems()).isEmpty();
        assertThat(view.cartVersion()).isEqualTo(7);
        assertThat(view.subtotalMinor()).isEqualTo(100_000);
        assertThat(view.deliveryChargeMinor()).isEqualTo(15_000);
        assertThat(view.itemsDiscountMinor()).isEqualTo(20_000);
        assertThat(view.serviceFeeMinor()).isEqualTo(2_000 + 2_500);
        assertThat(view.taxMinor()).isEqualTo(12_800);
        assertThat(view.tipMinor()).isEqualTo(5_000);
        assertThat(view.totalMinor()).isEqualTo(100_000 - 20_000 + 15_000 + 4_500 + 12_800 + 5_000);
        assertThat(view.etaMinutes()).isEqualTo(20 + 15);
        assertThat(view.paymentMethods()).containsExactly(PaymentMethod.CASH, PaymentMethod.CARD);
        ArgumentCaptor<CouponRequest> coupon = ArgumentCaptor.forClass(CouponRequest.class);
        verify(this.promotions).evaluate(coupon.capture());
        assertThat(coupon.getValue().code()).isEqualTo("welcome");
        assertThat(coupon.getValue().deliveryChargeMinor()).isEqualTo(15_000);
        assertThat(coupon.getValue().city()).isEqualTo("Lahore");
    }

    @Test
    @DisplayName("pickup skips delivery; a free-delivery coupon cannot take off more than the charge")
    void pickupAndFreeDelivery() {
        CheckoutView pickup = this.service.checkout(new CheckoutRequest("c1", Fulfilment.PICKUP, null, null, 0, PaymentMethod.CASH));
        assertThat(pickup.problems()).isEmpty();
        assertThat(pickup.delivery()).isNull();
        assertThat(pickup.deliveryChargeMinor()).isZero();
        assertThat(pickup.etaMinutes()).isEqualTo(20);
        verify(this.geo, never()).quote(any());

        given(this.promotions.evaluate(any())).willReturn(new CouponEvaluation(true, null, Map.of(), "p2", "FREEDEL", Map.of(), 0,
                99_999, 0));
        CheckoutView free = this.service.checkout(new CheckoutRequest("c1", Fulfilment.DELIVERY, HOME, "FREEDEL", 0, null));
        assertThat(free.deliveryDiscountMinor()).isEqualTo(15_000);
        assertThat(free.totalMinor()).isEqualTo(100_000 + 4_500 + 16_000);
    }

    @Test
    @DisplayName("lists every reason the order cannot be placed instead of failing")
    void problems() {
        given(this.carts.price(this.cart)).willReturn(CartFixtures.priced(CartFixtures.store("s1", true, false, false),
                List.of(), 5_000, 5_000));
        given(this.settings.getOrdering()).willReturn(new OrderingView(true, false, 0, 0, 0, false, 180, 1));
        given(this.promotions.evaluate(any())).willReturn(CouponEvaluation.rejected("OLD", "p3", CouponEvaluation.EXPIRED, Map.of()));

        CheckoutView delivery = this.service.checkout(new CheckoutRequest("c1", Fulfilment.DELIVERY, HOME, "OLD", 100,
                PaymentMethod.CARD));
        assertThat(delivery.problems()).extracting(CheckoutView.Problem::code).containsExactly(CheckoutView.STORE_CLOSED,
                CheckoutView.MIN_ORDER, CheckoutView.COUPON_INVALID, CheckoutView.TIP_INVALID,
                CheckoutView.PAYMENT_METHOD_UNAVAILABLE);
        assertThat(delivery.problems().get(2).details()).containsEntry("reason", CouponEvaluation.EXPIRED);
        assertThat(delivery.problems().get(1).details()).containsEntry("minOrderMinor", 50_000L);

        CheckoutView pickup = this.service.checkout(new CheckoutRequest("c1", Fulfilment.PICKUP, null, null, 0, null));
        assertThat(pickup.problems()).extracting(CheckoutView.Problem::code).contains(CheckoutView.PICKUP_UNAVAILABLE);

        CheckoutView noAddress = this.service.checkout(new CheckoutRequest("c1", null, null, null, 0, null));
        assertThat(noAddress.fulfilment()).isEqualTo(Fulfilment.DELIVERY);
        assertThat(noAddress.problems()).extracting(CheckoutView.Problem::code).contains(CheckoutView.ADDRESS_REQUIRED);

        given(this.geo.quote(any())).willReturn(new DeliveryQuoteView(false, DeliveryQuoteView.OUT_OF_AREA, 0, 0, null, 0, null,
                DeliveryMode.SELF));
        CheckoutView far = this.service.checkout(new CheckoutRequest("c1", Fulfilment.DELIVERY, HOME, null, 0, null));
        assertThat(far.problems()).extracting(CheckoutView.Problem::code).contains(CheckoutView.NOT_DELIVERABLE);
    }

    @Test
    @DisplayName("an empty cart has one problem and no store")
    void empty() {
        given(this.carts.find("c1")).willReturn(null);

        CheckoutView view = this.service.checkout(new CheckoutRequest("c1", Fulfilment.PICKUP, null, null, 0, null));

        assertThat(view.store()).isNull();
        assertThat(view.problems()).extracting(CheckoutView.Problem::code).containsExactly(CheckoutView.CART_EMPTY);
    }
}
