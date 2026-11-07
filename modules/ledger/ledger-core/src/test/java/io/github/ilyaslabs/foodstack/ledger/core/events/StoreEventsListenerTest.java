package io.github.ilyaslabs.foodstack.ledger.core.events;

import java.util.Map;

import io.github.ilyaslabs.foodstack.ledger.core.application.CreditLimits;
import io.github.ilyaslabs.foodstack.settings.api.events.SettingsChanged;
import io.github.ilyaslabs.foodstack.store.api.events.StorePaymentCollectionChanged;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class StoreEventsListenerTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private final CreditLimits limits = mock(CreditLimits.class);

    private final StoreEventsListener listener = new StoreEventsListener(this.limits, this.json);

    @Test
    @DisplayName("finance's permission and limit, and a new default limit, are checked; other events are ignored")
    void events() {
        this.listener.onEvent(envelope("foodstack.store.StorePaymentCollectionChanged.v1",
                new StorePaymentCollectionChanged("s1", "b1", "Napoli", java.util.Set.of("o1"), "DIRECT", null, "Signed agreement", "fin-1")));
        this.listener.onEvent(envelope("foodstack.settings.SettingsChanged.v1", new SettingsChanged("ordering", 2, "u1")));
        this.listener.onEvent(envelope("foodstack.settings.SettingsChanged.v1", new SettingsChanged("branding", 2, "u1")));
        this.listener.onEvent(envelope("foodstack.store.StoreChanged.v1", Map.of("storeId", "s1")));

        verify(this.limits).collectionChanged("s1", "b1", "Napoli", java.util.Set.of("o1"), "DIRECT", null);
        verify(this.limits).defaultChanged();
        verifyNoMoreInteractions(this.limits);
    }

    private String envelope(String type, Object data) {
        return this.json.writeValueAsString(Map.of("specversion", "1.0", "id", "e1", "source", "foodstack/x", "type", type,
                "subject", "s1", "time", "2026-11-07T12:00:00Z", "data", data));
    }
}
