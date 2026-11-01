package io.github.ilyaslabs.foodstack.payment.core.events;

import java.util.Map;
import java.util.Set;

import io.github.ilyaslabs.foodstack.order.api.OrderStatus;
import io.github.ilyaslabs.foodstack.order.api.OrderSummary;
import io.github.ilyaslabs.foodstack.order.api.events.OrderCancelled;
import io.github.ilyaslabs.foodstack.order.api.events.OrderRefunded;
import io.github.ilyaslabs.foodstack.order.api.events.OrderRejected;
import io.github.ilyaslabs.foodstack.payment.core.application.PaymentService;
import io.github.ilyaslabs.foodstack.platform.core.Fulfilment;
import io.github.ilyaslabs.foodstack.platform.core.PaymentMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class OrderEventsListenerTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private final PaymentService payments = mock(PaymentService.class);

    private final OrderEventsListener listener = new OrderEventsListener(this.payments, this.json);

    @Test
    @DisplayName("cancelled, rejected and refunded orders are refunded with the event id as the refund id")
    void refunds() {
        this.listener.onEvent(envelope("e1", "foodstack.order.OrderCancelled.v1",
                new OrderCancelled(summary(OrderStatus.CANCELLED), "c1", "changed my mind", 170_000)));
        verify(this.payments).refund("o1", 170_000, "e1", "Order cancelled: changed my mind");

        this.listener.onEvent(envelope("e2", "foodstack.order.OrderRejected.v1",
                new OrderRejected(summary(OrderStatus.REJECTED), "s1", "busy", false, 170_000)));
        verify(this.payments).refund("o1", 170_000, "e2", "Order rejected: busy");

        this.listener.onEvent(envelope("e3", "foodstack.order.OrderRefunded.v1",
                new OrderRefunded(summary(OrderStatus.COLLECTED), "support-1", 20_000, "Cold food")));
        verify(this.payments).refund("o1", 20_000, "e3", "Cold food");
    }

    @Test
    @DisplayName("other order events are ignored")
    void others() {
        PaymentService other = mock(PaymentService.class);
        new OrderEventsListener(other, this.json).onEvent(envelope("e4", "foodstack.order.OrderReady.v1", Map.of("x", 1)));
        verifyNoInteractions(other);
    }

    private String envelope(String id, String type, Object data) {
        return this.json.writeValueAsString(Map.of("specversion", "1.0", "id", id, "source", "foodstack/order", "type", type,
                "subject", "o1", "time", "2026-11-01T12:00:00Z", "data", data));
    }

    private static OrderSummary summary(OrderStatus status) {
        return new OrderSummary("o1", "AB12-CD34", "c1", "s1", "Spice Route Gulberg", Set.of("owner-1"), status,
                Fulfilment.PICKUP, PaymentMethod.CARD, 2, 170_000, "PKR", null, "en", 1_793_000_000L);
    }
}
