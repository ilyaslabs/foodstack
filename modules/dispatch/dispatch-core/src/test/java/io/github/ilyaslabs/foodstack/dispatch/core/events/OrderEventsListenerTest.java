package io.github.ilyaslabs.foodstack.dispatch.core.events;

import java.util.Map;
import java.util.Set;

import io.github.ilyaslabs.foodstack.dispatch.core.application.DispatchService;
import io.github.ilyaslabs.foodstack.geo.api.DeliveryMode;
import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import io.github.ilyaslabs.foodstack.order.api.OrderDelivery;
import io.github.ilyaslabs.foodstack.order.api.OrderStatus;
import io.github.ilyaslabs.foodstack.order.api.OrderSummary;
import io.github.ilyaslabs.foodstack.order.api.events.OrderAccepted;
import io.github.ilyaslabs.foodstack.order.api.events.OrderCancelled;
import io.github.ilyaslabs.foodstack.order.api.events.OrderDelivered;
import io.github.ilyaslabs.foodstack.order.api.events.OrderReady;
import io.github.ilyaslabs.foodstack.order.api.events.OrderRejected;
import io.github.ilyaslabs.foodstack.platform.core.Fulfilment;
import io.github.ilyaslabs.foodstack.platform.core.PaymentMethod;
import io.github.ilyaslabs.foodstack.platform.mongo.InboxGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class OrderEventsListenerTest {

    private static final OrderSummary ORDER = new OrderSummary("o1", "AB12-CD34", "c1", "s1", "Spice Route", Set.of("owner-1"),
            OrderStatus.ACCEPTED, Fulfilment.DELIVERY, PaymentMethod.CASH, 1, 1_000, "PKR", null, "en", 1_800_000_000L);

    private final JsonMapper json = JsonMapper.builder().build();

    private final DispatchService dispatch = mock(DispatchService.class);

    private final InboxGuard inbox = mock(InboxGuard.class);

    private final OrderEventsListener listener = new OrderEventsListener(this.dispatch, this.inbox, this.json);

    @BeforeEach
    void runHandlers() {
        given(this.inbox.processOnce(any(), any(), any())).willAnswer((invocation) -> {
            ((Runnable) invocation.getArgument(2)).run();
            return true;
        });
    }

    @Test
    @DisplayName("accepted delivery orders become jobs; pickup orders do not; ready, cancel and reject reach the job")
    void orders() {
        OrderDelivery delivery = new OrderDelivery(DeliveryMode.OPERATOR, null, new GeoPoint(1, 2), "a", new GeoPoint(1.01, 2), "b",
                null, "Sara", null, 15_000, 0, 1_000, "PKR", null, 15_000);
        this.listener.onEvent(envelope("e1", "OrderAccepted", new OrderAccepted(ORDER, "store:u1", 20, 1_800_001_200L, delivery)));
        verify(this.dispatch).orderAccepted(ORDER, delivery, 1_800_001_200L, "store:u1");
        verify(this.inbox).processOnce(eq("e1"), eq("dispatch-orders"), any());
        this.listener.onEvent(envelope("e2", "OrderAccepted", new OrderAccepted(ORDER, "store:u1", 20, 1L, null)));
        verify(this.dispatch, never()).orderAccepted(any(), eq(null), anyLong(), any());

        this.listener.onEvent(envelope("e3", "OrderReady", new OrderReady(ORDER, "store:u1")));
        verify(this.dispatch).orderReady("o1");
        this.listener.onEvent(envelope("e4", "OrderCancelled", new OrderCancelled(ORDER, "support:x", "Duplicate", 0)));
        verify(this.dispatch).orderClosed("o1", "Duplicate");
        this.listener.onEvent(envelope("e5", "OrderRejected", new OrderRejected(ORDER, "system", "No answer", true, 0)));
        verify(this.dispatch).orderClosed("o1", "No answer");
        this.listener.onEvent(envelope("e6", "OrderDelivered", new OrderDelivered(ORDER, "store:u1")));
        verify(this.dispatch).orderDeliveredElsewhere("o1", "store:u1");
    }

    private String envelope(String id, String type, Object data) {
        return this.json.writeValueAsString(Map.of("specversion", "1.0", "id", id, "source", "foodstack/order", "type",
                "foodstack.order." + type + ".v1", "subject", "o1", "time", "2026-11-01T12:00:00Z", "data", data));
    }
}
