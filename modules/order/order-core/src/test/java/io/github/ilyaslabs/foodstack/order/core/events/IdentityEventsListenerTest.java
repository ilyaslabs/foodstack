package io.github.ilyaslabs.foodstack.order.core.events;

import io.github.ilyaslabs.foodstack.order.core.application.CustomerErasure;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class IdentityEventsListenerTest {

    private final CustomerErasure erasure = mock(CustomerErasure.class);

    private final IdentityEventsListener listener = new IdentityEventsListener(this.erasure, JsonMapper.builder().build());

    private static String event(String type, String data) {
        return "{\"specversion\":\"1.0\",\"id\":\"e1\",\"source\":\"/foodstack/identity\",\"type\":\"" + type
                + "\",\"subject\":\"u1\",\"time\":\"2026-11-07T10:00:00Z\",\"data\":" + data + "}";
    }

    @Test
    @DisplayName("UserDeleted anonymises the customer's orders; other identity events are ignored")
    void userDeleted() {
        // when
        this.listener.onEvent(event("foodstack.identity.UserRegistered.v1", "{\"userId\":\"u1\"}"));
        this.listener.onEvent(event("foodstack.identity.UserDeleted.v1", "{\"userId\":\"u1\"}"));

        // then
        verify(this.erasure).erase("u1");
        verifyNoMoreInteractions(this.erasure);
    }
}
