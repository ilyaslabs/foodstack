package io.github.ilyaslabs.foodstack.payment.core.events;

import java.util.Map;

import io.github.ilyaslabs.foodstack.identity.api.events.UserDeleted;
import io.github.ilyaslabs.foodstack.payment.core.application.PaymentService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class IdentityEventsListenerTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private final PaymentService payments = mock(PaymentService.class);

    private final IdentityEventsListener listener = new IdentityEventsListener(this.payments, this.json);

    @Test
    @DisplayName("a deleted customer's saved cards are forgotten; other identity events are ignored")
    void events() {
        this.listener.onEvent(envelope("foodstack.identity.UserDeleted.v1", new UserDeleted("u1")));
        this.listener.onEvent(envelope("foodstack.identity.UserBlocked.v1", Map.of("userId", "u2")));

        verify(this.payments).eraseCustomer("u1");
        verifyNoMoreInteractions(this.payments);
    }

    private String envelope(String type, Object data) {
        return this.json.writeValueAsString(Map.of("specversion", "1.0", "id", "e1", "source", "foodstack/identity",
                "type", type, "subject", "u1", "time", "2026-11-07T12:00:00Z", "data", data));
    }
}
