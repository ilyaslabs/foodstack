package io.github.ilyaslabs.foodstack.order.core.events;

import java.util.Map;
import java.util.Set;

import io.github.ilyaslabs.foodstack.dispatch.api.DeliveryStatus;
import io.github.ilyaslabs.foodstack.dispatch.api.DeliverySummary;
import io.github.ilyaslabs.foodstack.dispatch.api.events.DeliveryCompleted;
import io.github.ilyaslabs.foodstack.dispatch.api.events.DeliveryHandedBack;
import io.github.ilyaslabs.foodstack.dispatch.api.events.DeliveryPickedUp;
import io.github.ilyaslabs.foodstack.dispatch.api.events.RiderAssigned;
import io.github.ilyaslabs.foodstack.dispatch.api.events.RiderUnassigned;
import io.github.ilyaslabs.foodstack.geo.api.DeliveryMode;
import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import io.github.ilyaslabs.foodstack.order.api.OrderStatus;
import io.github.ilyaslabs.foodstack.order.core.application.Actor;
import io.github.ilyaslabs.foodstack.order.core.application.OrderLifecycleService;
import io.github.ilyaslabs.foodstack.order.core.persistence.OrderDocument;
import io.github.ilyaslabs.foodstack.platform.mongo.InboxGuard;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class DispatchEventsListenerTest {

    private static final DeliverySummary DELIVERY = new DeliverySummary("o1", "AB12-CD34", "c1", "s1", "Spice Route",
            Set.of("owner-1"), DeliveryMode.OPERATOR, DeliveryStatus.ASSIGNED, "r1", "Ali", new GeoPoint(31.52, 74.35),
            new GeoPoint(31.5, 74.3), "en");

    private final JsonMapper json = JsonMapper.builder().build();

    private final OrderLifecycleService lifecycle = mock(OrderLifecycleService.class);

    private final InboxGuard inbox = mock(InboxGuard.class);

    private final DispatchEventsListener listener = new DispatchEventsListener(this.lifecycle, this.inbox, this.json);

    @BeforeEach
    void runHandlers() {
        given(this.inbox.processOnce(any(), any(), any())).willAnswer((invocation) -> {
            ((Runnable) invocation.getArgument(2)).run();
            return true;
        });
    }

    @Test
    @DisplayName("assigning, unassigning and handing back reach the order once each")
    void riders() {
        this.listener.onEvent(envelope("e1", "RiderAssigned", new RiderAssigned(DELIVERY, "r1", "Ali", "CAR", "rider:r1")));
        verify(this.inbox).processOnce(eq("e1"), eq("order-dispatch"), any());
        verify(this.lifecycle).riderAssigned("o1", "r1", "Ali", "CAR", "rider:r1");
        this.listener.onEvent(envelope("e2", "RiderUnassigned", new RiderUnassigned(DELIVERY, "r1", "Gave up", "rider:r1")));
        verify(this.lifecycle).riderUnassigned("o1", "r1", "Gave up", "rider:r1");
        this.listener.onEvent(envelope("e3", "DeliveryHandedBack", new DeliveryHandedBack(DELIVERY, null, "support:s", "Busy")));
        verify(this.lifecycle).handedBack("o1", "Busy", "support:s");
    }

    @Test
    @DisplayName("a completed delivery marks the order picked up first when the rider skipped that step")
    void completed() {
        OrderDocument order = mock(OrderDocument.class);
        given(order.status()).willReturn(OrderStatus.READY);
        given(this.lifecycle.require("o1")).willReturn(order);
        this.listener.onEvent(envelope("e4", "DeliveryCompleted",
                new DeliveryCompleted(DELIVERY, "rider:r1", "OPERATOR", 15_000, 10_500, 0, 0, "PKR", 2_400, "CODE")));
        verify(this.lifecycle).pickedUp("o1", Actor.rider("r1"));
        verify(this.lifecycle).delivered("o1", Actor.rider("r1"));
    }

    @Test
    @DisplayName("a pickup for an order cancelled meanwhile is dropped, not retried")
    void stale() {
        given(this.lifecycle.pickedUp(any(), any())).willThrow(new ModuleException.Conflict("changed", "ORDER_STATUS_CHANGED"));
        assertThatCode(() -> this.listener.onEvent(envelope("e5", "DeliveryPickedUp", new DeliveryPickedUp(DELIVERY, "rider:r1"))))
            .doesNotThrowAnyException();
        verify(this.lifecycle, never()).delivered(any(), any());
    }

    private String envelope(String id, String type, Object data) {
        return this.json.writeValueAsString(Map.of("specversion", "1.0", "id", id, "source", "foodstack/dispatch", "type",
                "foodstack.dispatch." + type + ".v1", "subject", "o1", "time", "2026-11-01T12:00:00Z", "data", data));
    }
}
