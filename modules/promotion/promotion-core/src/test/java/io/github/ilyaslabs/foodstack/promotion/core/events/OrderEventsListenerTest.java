package io.github.ilyaslabs.foodstack.promotion.core.events;

import java.util.Map;
import java.util.Set;

import io.github.ilyaslabs.foodstack.order.api.OrderStatus;
import io.github.ilyaslabs.foodstack.order.api.OrderSummary;
import io.github.ilyaslabs.foodstack.order.api.events.OrderCancelled;
import io.github.ilyaslabs.foodstack.order.api.events.OrderConfirmed;
import io.github.ilyaslabs.foodstack.platform.core.Fulfilment;
import io.github.ilyaslabs.foodstack.platform.core.PaymentMethod;
import io.github.ilyaslabs.foodstack.platform.mongo.InboxGuard;
import io.github.ilyaslabs.foodstack.promotion.core.application.CouponService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class OrderEventsListenerTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private final CouponService coupons = mock(CouponService.class);

    private final InboxGuard inbox = mock(InboxGuard.class);

    private final OrderEventsListener listener = new OrderEventsListener(this.coupons, this.inbox, this.json);

    @BeforeEach
    void runHandlers() {
        given(this.inbox.processOnce(any(), any(), any())).willAnswer((invocation) -> {
            ((Runnable) invocation.getArgument(2)).run();
            return true;
        });
    }

    @Test
    @DisplayName("a confirmed order redeems its coupon and counts as the customer's order, once per event")
    void confirmed() {
        this.listener.onEvent(envelope("e1", "foodstack.order.OrderConfirmed.v1",
                new OrderConfirmed(summary(OrderStatus.CONFIRMED), "payment", null)));

        verify(this.inbox).processOnce(eq("e1"), eq("promotion-orders"), any());
        verify(this.coupons).redeem("o1");
        verify(this.coupons).recordConfirmedOrder("c1");
    }

    @Test
    @DisplayName("a cancelled order gives its coupon use back; other events are ignored")
    void cancelled() {
        this.listener.onEvent(envelope("e2", "foodstack.order.OrderCancelled.v1",
                new OrderCancelled(summary(OrderStatus.CANCELLED), "c1", "changed my mind", 0)));
        verify(this.coupons).release("o1");

        CouponService other = mock(CouponService.class);
        new OrderEventsListener(other, this.inbox, this.json).onEvent(envelope("e3", "foodstack.order.OrderReady.v1",
                Map.of("x", 1)));
        verifyNoInteractions(other);
    }

    private String envelope(String id, String type, Object data) {
        return this.json.writeValueAsString(Map.of("specversion", "1.0", "id", id, "source", "foodstack/order", "type", type,
                "subject", "o1", "time", "2026-11-01T12:00:00Z", "data", data));
    }

    private static OrderSummary summary(OrderStatus status) {
        return new OrderSummary("o1", "AB12-CD34", "c1", "s1", "Spice Route Gulberg", Set.of("owner-1"), status,
                Fulfilment.PICKUP, PaymentMethod.CASH, 2, 170_000, "PKR", "p1", "en", 1_793_000_000L);
    }
}
