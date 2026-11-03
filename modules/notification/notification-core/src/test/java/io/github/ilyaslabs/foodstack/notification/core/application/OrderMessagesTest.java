package io.github.ilyaslabs.foodstack.notification.core.application;

import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.notification.core.application.NotificationService.Message;
import io.github.ilyaslabs.foodstack.order.api.OrderApi;
import io.github.ilyaslabs.foodstack.order.api.OrderStatus;
import io.github.ilyaslabs.foodstack.order.api.OrderSummary;
import io.github.ilyaslabs.foodstack.order.api.events.OrderCancelled;
import io.github.ilyaslabs.foodstack.order.api.events.OrderConfirmed;
import io.github.ilyaslabs.foodstack.order.api.events.OrderReady;
import io.github.ilyaslabs.foodstack.order.api.events.OrderRejected;
import io.github.ilyaslabs.foodstack.platform.core.Fulfilment;
import io.github.ilyaslabs.foodstack.platform.core.PaymentMethod;
import io.github.ilyaslabs.foodstack.platform.security.PiiCipher;
import io.github.ilyaslabs.foodstack.platform.security.ServiceCalls;
import io.github.ilyaslabs.foodstack.settings.api.LocaleView;
import io.github.ilyaslabs.foodstack.settings.api.OrderingView;
import io.github.ilyaslabs.foodstack.settings.api.SettingsApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class OrderMessagesTest {

    private static final PiiCipher PII = new PiiCipher(java.util.Base64.getEncoder().encodeToString(new byte[32]));

    private final NotificationService notifications = mock(NotificationService.class);

    private final SettingsApi settings = mock(SettingsApi.class);

    private final OrderMessages messages = new OrderMessages(this.notifications, new Templates(JsonMapper.builder().build()),
            this.settings, mock(OrderApi.class), mock(ServiceCalls.class), PII);

    @BeforeEach
    void settings() {
        given(this.settings.getOrdering()).willReturn(new OrderingView(true, true, 0, 0, 0, true, 180, 1));
        given(this.settings.getLocale()).willReturn(new LocaleView("en", List.of("en", "ar"), "PKR", "Asia/Karachi", 1));
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("a confirmed order tells the customer, and every person of the branch that a new order waits")
    void confirmed() {
        this.messages.onOrderEvent("e1", new OrderConfirmed(order(Fulfilment.PICKUP, "ar"), "payment", null));
        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        verify(this.notifications, times(3)).send(sent.capture());
        assertThat(sent.getAllValues()).extracting(Message::userId).containsExactly("c1", "owner-1", "owner-2");
        assertThat(sent.getAllValues().get(0).language()).isEqualTo("ar");
        assertThat((Map<String, Object>) sent.getAllValues().get(0).params()).containsEntry("codeLine", "");
        Message store = sent.getAllValues().get(1);
        assertThat(store.template()).isEqualTo("store-new-order");
        assertThat(store.backOffice()).isTrue();
        assertThat((Map<String, Object>) store.params()).containsEntry("total", "PKR 1,700.00").containsEntry("minutes", 3)
            .containsEntry("fulfilment", "pickup");
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("rejections explain why and mention the refund; customers are not told about their own cancel")
    void closed() {
        this.messages.onOrderEvent("e2", new OrderRejected(order(Fulfilment.PICKUP, "en"), "system", "x", true, 170_000));
        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        verify(this.notifications).send(sent.capture());
        assertThat((Map<String, Object>) sent.getValue().params()).containsEntry("reason", "The restaurant did not answer in time.")
            .containsEntry("refund", "PKR 1,700.00 will be refunded to your card.");

        NotificationService quiet = mock(NotificationService.class);
        OrderMessages other = new OrderMessages(quiet, new Templates(JsonMapper.builder().build()), this.settings,
                mock(OrderApi.class), mock(ServiceCalls.class), PII);
        other.onOrderEvent("e3", new OrderCancelled(order(Fulfilment.PICKUP, "en"), "customer:c1", "Changed my mind", 0));
        other.onOrderEvent("e4", new OrderReady(order(Fulfilment.DELIVERY, "en"), "store:owner-1"));
        verifyNoInteractions(quiet);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("a delivery that needs a code has it in the confirmation email (PRD DR-6)")
    void deliveryCode() {
        this.messages.onOrderEvent("e5", new OrderConfirmed(order(Fulfilment.DELIVERY, "en"), "payment", PII.encrypt("3173")));
        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        verify(this.notifications, times(3)).send(sent.capture());
        Message customer = sent.getAllValues().get(0);
        assertThat(customer.template()).isEqualTo("order-confirmed");
        assertThat(customer.email()).isTrue();
        assertThat((Map<String, Object>) customer.params()).containsEntry("codeLine",
                "Your delivery code is 3173. Give it to the rider when your order arrives.");
    }

    private static OrderSummary order(Fulfilment fulfilment, String language) {
        return new OrderSummary("o1", "7F3A-91C2", "c1", "s1", "Karahi Corner", new java.util.LinkedHashSet<>(List.of("owner-1",
                "owner-2")), OrderStatus.CONFIRMED, fulfilment, PaymentMethod.CARD, 2, 170_000, "PKR", null, language, 0);
    }
}
