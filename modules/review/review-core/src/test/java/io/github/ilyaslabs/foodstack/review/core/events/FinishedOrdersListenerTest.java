package io.github.ilyaslabs.foodstack.review.core.events;

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
import io.github.ilyaslabs.foodstack.order.api.OrderStatus;
import io.github.ilyaslabs.foodstack.order.api.OrderSummary;
import io.github.ilyaslabs.foodstack.order.api.events.OrderCollected;
import io.github.ilyaslabs.foodstack.order.api.events.OrderDelivered;
import io.github.ilyaslabs.foodstack.platform.core.Fulfilment;
import io.github.ilyaslabs.foodstack.platform.core.PaymentMethod;
import io.github.ilyaslabs.foodstack.review.core.application.ReviewService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class FinishedOrdersListenerTest {

    private static final Instant AT = Instant.parse("2026-11-07T12:00:00Z");

    private final JsonMapper json = JsonMapper.builder().build();

    private final ReviewService reviews = mock(ReviewService.class);

    private final FinishedOrdersListener listener = new FinishedOrdersListener(this.reviews, this.json,
            Clock.fixed(AT, ZoneOffset.UTC));

    @Test
    @DisplayName("delivered and collected orders become rateable, a completed delivery names the rider, a deleted customer is anonymised, the rest is ignored")
    void events() {
        this.listener.onEvent(envelope("foodstack.order.OrderDelivered.v1", new OrderDelivered(order("o1", Fulfilment.DELIVERY), "rider:r1")));
        this.listener.onEvent(envelope("foodstack.order.OrderCollected.v1", new OrderCollected(order("o2", Fulfilment.PICKUP), "c1")));
        DeliverySummary d = new DeliverySummary("o1", "N", "c1", "s1", "Napoli", Set.of(), DeliveryMode.OPERATOR,
                DeliveryStatus.DELIVERED, "r1", "Hamza", new GeoPoint(1, 1), new GeoPoint(1, 1), "en");
        this.listener.onEvent(envelope("foodstack.dispatch.DeliveryCompleted.v1",
                new DeliveryCompleted(d, "rider:r1", "OPERATOR", 100, 70, 10, 0, "PKR", 1_000, "TAP")));
        this.listener.onEvent(envelope("foodstack.order.OrderPlaced.v1", Map.of("orderId", "o3")));
        this.listener.onEvent(envelope("foodstack.identity.UserDeleted.v1", Map.of("userId", "c9")));

        verify(this.reviews).orderFinished("o1", "N-o1", "c1", "s1", null, "Napoli", Set.of("u1"), "en", AT);
        verify(this.reviews).orderFinished("o2", "N-o2", "c1", "s1", null, "Napoli", Set.of("u1"), "en", AT);
        verify(this.reviews).delivered("o1", "r1", "Hamza", "OPERATOR");
        verify(this.reviews).eraseCustomer("c9");
        verifyNoMoreInteractions(this.reviews);
    }

    private static OrderSummary order(String id, Fulfilment fulfilment) {
        return new OrderSummary(id, "N-" + id, "c1", "s1", "Napoli", Set.of("u1"), OrderStatus.DELIVERED, fulfilment,
                PaymentMethod.CASH, 1, 100, "PKR", null, "en", 0);
    }

    private String envelope(String type, Object data) {
        return this.json.writeValueAsString(Map.of("specversion", "1.0", "id", "e1", "source", "foodstack/x", "type", type,
                "subject", "o1", "time", "2026-11-07T12:00:00Z", "data", data));
    }
}
