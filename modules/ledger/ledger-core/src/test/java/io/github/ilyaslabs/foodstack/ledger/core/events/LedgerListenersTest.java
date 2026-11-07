package io.github.ilyaslabs.foodstack.ledger.core.events;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import io.github.ilyaslabs.foodstack.dispatch.api.DeliveryStatus;
import io.github.ilyaslabs.foodstack.dispatch.api.DeliverySummary;
import io.github.ilyaslabs.foodstack.dispatch.api.events.DeliveryCompleted;
import io.github.ilyaslabs.foodstack.geo.api.DeliveryMode;
import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import io.github.ilyaslabs.foodstack.ledger.core.application.CreditLimits;
import io.github.ilyaslabs.foodstack.ledger.core.application.LedgerPosting;
import io.github.ilyaslabs.foodstack.order.api.OrderStatus;
import io.github.ilyaslabs.foodstack.order.api.OrderSummary;
import io.github.ilyaslabs.foodstack.order.api.events.OrderCancelled;
import io.github.ilyaslabs.foodstack.order.api.events.OrderCollected;
import io.github.ilyaslabs.foodstack.order.api.events.OrderDelivered;
import io.github.ilyaslabs.foodstack.order.api.events.OrderPlaced;
import io.github.ilyaslabs.foodstack.order.api.events.OrderRefunded;
import io.github.ilyaslabs.foodstack.payment.api.events.PaymentSucceeded;
import io.github.ilyaslabs.foodstack.payment.api.events.RefundSucceeded;
import io.github.ilyaslabs.foodstack.platform.core.Fulfilment;
import io.github.ilyaslabs.foodstack.platform.core.PaymentMethod;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** The ledger's listeners hand each money event to {@link LedgerPosting} (mocked) and ignore the rest. */
class LedgerListenersTest {

    private static final Instant AT = Instant.parse("2026-11-06T12:00:00Z");

    private final JsonMapper json = JsonMapper.builder().build();

    private final Clock clock = Clock.fixed(AT, ZoneOffset.UTC);

    private static OrderSummary order(OrderStatus status) {
        return new OrderSummary("o1", "N", "c1", "s1", "Napoli", Set.of(), status, Fulfilment.DELIVERY, PaymentMethod.CARD, 1,
                100, "PKR", null, "en", 0);
    }

    @Test
    @DisplayName("order events: placed, delivered, collected, refunded and closed reach the posting rules")
    void orders() {
        LedgerPosting posting = mock(LedgerPosting.class);
        CreditLimits limits = mock(CreditLimits.class);
        OrderEventsListener listener = new OrderEventsListener(posting, this.json, this.clock, limits);
        listener.onEvent(envelope("foodstack.order.OrderPlaced.v1", new OrderPlaced(order(OrderStatus.PLACED), "c1")));
        listener.onEvent(envelope("foodstack.order.OrderDelivered.v1", new OrderDelivered(order(OrderStatus.DELIVERED), "r")));
        listener.onEvent(envelope("foodstack.order.OrderCollected.v1", new OrderCollected(order(OrderStatus.COLLECTED), "s")));
        listener.onEvent(envelope("foodstack.order.OrderRefunded.v1",
                new OrderRefunded(order(OrderStatus.DELIVERED), "u", 50, "Cold", OrderRefunded.OPERATOR)));
        listener.onEvent(envelope("foodstack.order.OrderCancelled.v1",
                new OrderCancelled(order(OrderStatus.CANCELLED), "u", "x", 0)));
        verify(posting).orderPlaced(any());
        verify(posting, org.mockito.Mockito.times(2)).orderDone(any(), eq(AT));
        verify(posting).orderRefunded(eq("e1"), any(), eq(AT));
        verify(posting).orderClosed("o1");
        verify(limits, org.mockito.Mockito.times(3)).check("s1");

        LedgerPosting other = mock(LedgerPosting.class);
        new OrderEventsListener(other, this.json, this.clock).onEvent(envelope("foodstack.order.OrderAccepted.v1",
                Map.of("order", Map.of())));
        verifyNoInteractions(other);
    }

    @Test
    @DisplayName("payment and dispatch events: card money in and out, and deliveries")
    void paymentsAndDeliveries() {
        LedgerPosting posting = mock(LedgerPosting.class);
        PaymentEventsListener payments = new PaymentEventsListener(posting, this.json, this.clock);
        payments.onEvent(envelope("foodstack.payment.PaymentSucceeded.v1", new PaymentSucceeded("o1", 100, "PKR", "g", "r")));
        payments.onEvent(envelope("foodstack.payment.RefundSucceeded.v1", new RefundSucceeded("o1", "rf", 50, 50, "PKR")));
        payments.onEvent(envelope("foodstack.payment.PaymentFailed.v1", Map.of("orderId", "o1")));
        verify(posting).paymentReceived(any(), eq(AT));
        verify(posting).refundPaidOut(any(), eq(AT));

        DispatchEventsListener dispatch = new DispatchEventsListener(posting, this.json, this.clock);
        DeliverySummary d = new DeliverySummary("o1", "N", "c1", "s1", "Napoli", Set.of(), DeliveryMode.OPERATOR,
                DeliveryStatus.DELIVERED, "r1", "Hamza", new GeoPoint(1, 1), new GeoPoint(1, 1), "en");
        dispatch.onEvent(envelope("foodstack.dispatch.DeliveryCompleted.v1",
                new DeliveryCompleted(d, "rider:r1", "OPERATOR", 100, 70, 10, 0, "PKR", 1_000, "TAP")));
        dispatch.onEvent(envelope("foodstack.dispatch.RiderAssigned.v1", Map.of("riderId", "r1")));
        verify(posting).deliveryCompleted(any(), eq(AT));
    }

    private String envelope(String type, Object data) {
        return this.json.writeValueAsString(Map.of("specversion", "1.0", "id", "e1", "source", "foodstack/x", "type", type,
                "subject", "o1", "time", "2026-11-06T12:00:00Z", "data", data));
    }
}
