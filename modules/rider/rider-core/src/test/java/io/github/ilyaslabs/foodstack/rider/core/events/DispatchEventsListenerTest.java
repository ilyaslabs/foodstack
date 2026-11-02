package io.github.ilyaslabs.foodstack.rider.core.events;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import io.github.ilyaslabs.foodstack.dispatch.api.DeliveryStatus;
import io.github.ilyaslabs.foodstack.dispatch.api.DeliverySummary;
import io.github.ilyaslabs.foodstack.dispatch.api.events.DeliveryCancelled;
import io.github.ilyaslabs.foodstack.dispatch.api.events.DeliveryCompleted;
import io.github.ilyaslabs.foodstack.dispatch.api.events.DeliveryPickedUp;
import io.github.ilyaslabs.foodstack.dispatch.api.events.RiderAssigned;
import io.github.ilyaslabs.foodstack.dispatch.api.events.RiderOffered;
import io.github.ilyaslabs.foodstack.geo.api.DeliveryMode;
import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import io.github.ilyaslabs.foodstack.platform.mongo.InboxGuard;
import io.github.ilyaslabs.foodstack.rider.core.application.RiderActivity;
import io.github.ilyaslabs.foodstack.rider.core.persistence.AssignmentDocument;
import io.github.ilyaslabs.foodstack.rider.core.persistence.EarningDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DispatchEventsListenerTest {

    private static final DeliverySummary DELIVERY = new DeliverySummary("o1", "AB12-CD34", "c1", "s1", "Spice Route",
            Set.of("owner-1"), DeliveryMode.OPERATOR, DeliveryStatus.ASSIGNED, "r1", "Ali", new GeoPoint(31.52, 74.35),
            new GeoPoint(31.5, 74.3), "en");

    private final JsonMapper json = JsonMapper.builder().build();

    private final RiderActivity activity = mock(RiderActivity.class);

    private final InboxGuard inbox = mock(InboxGuard.class);

    private final DispatchEventsListener listener = new DispatchEventsListener(this.activity, this.inbox, this.json,
            Clock.fixed(Instant.parse("2026-11-03T09:00:00Z"), ZoneOffset.UTC));

    @BeforeEach
    void runHandlers() {
        given(this.inbox.processOnce(any(), any(), any())).willAnswer((invocation) -> {
            ((Runnable) invocation.getArgument(2)).run();
            return true;
        });
    }

    @Test
    @DisplayName("offers count, an accepted offer becomes the rider's delivery, pickup and cancel follow")
    void lifecycle() {
        this.listener.onEvent(envelope("e1", "RiderOffered", new RiderOffered(DELIVERY, "r1", 1_800_000_030L, 10_500, "PKR")));
        verify(this.activity).offered("r1");

        this.listener.onEvent(envelope("e2", "RiderAssigned", new RiderAssigned(DELIVERY, "r1", "Ali", "CAR", "rider:r1")));
        ArgumentCaptor<AssignmentDocument> assignment = ArgumentCaptor.forClass(AssignmentDocument.class);
        verify(this.activity).assigned(assignment.capture(), eq(true));
        assertThat(assignment.getValue().customerId()).isEqualTo("c1");
        assertThat(assignment.getValue().dropoffLat()).isEqualTo(31.5);
        verify(this.inbox).processOnce(eq("e2"), eq("rider-dispatch"), any());

        this.listener.onEvent(envelope("e3", "DeliveryPickedUp", new DeliveryPickedUp(DELIVERY, "rider:r1")));
        verify(this.activity).pickedUp("o1");
        this.listener.onEvent(envelope("e4", "DeliveryCancelled", new DeliveryCancelled(DELIVERY, "r1", "Store closed")));
        verify(this.activity).released("o1", "r1");
    }

    @Test
    @DisplayName("a completed delivery records the earning with tip and cash")
    void completed() {
        this.listener.onEvent(envelope("e5", "DeliveryCompleted",
                new DeliveryCompleted(DELIVERY, "rider:r1", "OPERATOR", 15_000, 10_500, 2_000, 0, "PKR", 2_400, "CODE")));
        ArgumentCaptor<EarningDocument> earning = ArgumentCaptor.forClass(EarningDocument.class);
        verify(this.activity).delivered(earning.capture());
        assertThat(earning.getValue()).isEqualTo(new EarningDocument("o1", "r1", "AB12-CD34", "Spice Route", 10_500, 2_000, 0,
                "PKR", 2_400, Instant.parse("2026-11-01T12:00:00Z")));
    }

    private String envelope(String id, String type, Object data) {
        return this.json.writeValueAsString(Map.of("specversion", "1.0", "id", id, "source", "foodstack/dispatch", "type",
                "foodstack.dispatch." + type + ".v1", "subject", "o1", "time", "2026-11-01T12:00:00Z", "data", data));
    }
}
