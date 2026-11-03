package io.github.ilyaslabs.foodstack.notification.core.application;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import io.github.ilyaslabs.foodstack.dispatch.api.DeliveryStatus;
import io.github.ilyaslabs.foodstack.dispatch.api.DeliverySummary;
import io.github.ilyaslabs.foodstack.dispatch.api.events.DispatchAlert;
import io.github.ilyaslabs.foodstack.dispatch.api.events.RiderAssigned;
import io.github.ilyaslabs.foodstack.dispatch.api.events.RiderOffered;
import io.github.ilyaslabs.foodstack.geo.api.DeliveryMode;
import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import io.github.ilyaslabs.foodstack.identity.api.IdentityApi;
import io.github.ilyaslabs.foodstack.identity.api.Role;
import io.github.ilyaslabs.foodstack.notification.core.application.NotificationService.Message;
import io.github.ilyaslabs.foodstack.platform.security.ServiceCalls;
import io.github.ilyaslabs.foodstack.settings.api.LocaleView;
import io.github.ilyaslabs.foodstack.settings.api.SettingsApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class DispatchMessagesTest {

    private final NotificationService notifications = mock(NotificationService.class);

    private final SettingsApi settings = mock(SettingsApi.class);

    private final IdentityApi identity = mock(IdentityApi.class);

    private final ServiceCalls serviceCalls = mock(ServiceCalls.class);

    private final DispatchMessages messages = new DispatchMessages(this.notifications, this.settings, this.identity,
            this.serviceCalls);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        given(this.settings.getLocale()).willReturn(new LocaleView("en", List.of("en", "ur"), "PKR", "Asia/Karachi", 1));
        given(this.serviceCalls.call(any())).willAnswer((invocation) -> ((Supplier<Object>) invocation.getArgument(0)).get());
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("an offer rings only the rider's phone, with what the job pays")
    void offer() {
        this.messages.onOffered("e1", new RiderOffered(delivery(), "r1", 1_000, 15_000, "PKR"));
        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        verify(this.notifications).send(sent.capture());
        Message m = sent.getValue();
        assertThat(m.userId()).isEqualTo("r1");
        assertThat(m.push()).isTrue();
        assertThat(m.inApp()).isFalse();
        assertThat(m.email()).isFalse();
        assertThat(m.backOffice()).isTrue();
        assertThat((Map<String, Object>) m.params()).containsEntry("earning", "PKR 150.00");
        new Templates(JsonMapper.builder().build()).render(m.template(), m.language(), m.params());
    }

    @Test
    @DisplayName("the customer hears who is coming; a rider given the job by the store is told too")
    void assigned() {
        this.messages.onAssigned("e2", new RiderAssigned(delivery(), "r1", "Bilal", "MOTORBIKE", "rider:r1"));
        this.messages.onAssigned("e3", new RiderAssigned(delivery(), "r2", "Hamza", "BICYCLE", "store:owner-1"));
        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        verify(this.notifications, times(3)).send(sent.capture());
        assertThat(sent.getAllValues()).extracting(Message::userId, Message::template)
            .containsExactly(org.assertj.core.groups.Tuple.tuple("c1", "rider-assigned"),
                    org.assertj.core.groups.Tuple.tuple("c1", "rider-assigned"),
                    org.assertj.core.groups.Tuple.tuple("r2", "rider-job-assigned"));
        assertThat(sent.getAllValues().get(0).language()).isEqualTo("ur");
        assertThat(sent.getAllValues().get(0).link()).isEqualTo("/orders/o1");
    }

    @Test
    @DisplayName("no rider in time alerts every admin and support person in the notification centre")
    void alert() {
        given(this.identity.userIdsWithRoles(Set.of(Role.ADMIN, Role.SUPPORT))).willReturn(List.of("a1", "s1"));
        this.messages.onAlert("e4", new DispatchAlert(delivery(), "NO_RIDER", 300));
        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        verify(this.notifications, times(2)).send(sent.capture());
        assertThat(sent.getAllValues()).extracting(Message::userId).containsExactly("a1", "s1");
        assertThat(sent.getAllValues()).allSatisfy((m) -> {
            assertThat(m.inApp()).isTrue();
            assertThat(m.link()).isEqualTo(DispatchMessages.DISPATCH_LINK);
            assertThat(m.params().get("minutes")).isEqualTo(5L);
        });
    }

    private static DeliverySummary delivery() {
        return new DeliverySummary("o1", "7F3A-91C2", "c1", "s1", "Karahi Corner Gulberg", Set.of("owner-1"),
                DeliveryMode.OPERATOR, DeliveryStatus.ASSIGNED, null, null, new GeoPoint(31.5, 74.3), new GeoPoint(31.52, 74.35),
                "ur");
    }
}
