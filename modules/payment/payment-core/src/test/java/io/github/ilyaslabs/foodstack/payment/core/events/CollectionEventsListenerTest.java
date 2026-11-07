package io.github.ilyaslabs.foodstack.payment.core.events;

import java.util.Map;

import io.github.ilyaslabs.foodstack.ledger.api.events.StoreCreditLimitExceeded;
import io.github.ilyaslabs.foodstack.ledger.api.events.StoreCreditRestored;
import io.github.ilyaslabs.foodstack.payment.core.application.MerchantAccounts;
import io.github.ilyaslabs.foodstack.store.api.events.StorePaymentCollectionChanged;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class CollectionEventsListenerTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private final MerchantAccounts accounts = mock(MerchantAccounts.class);

    private final CollectionEventsListener listener = new CollectionEventsListener(this.accounts, this.json);

    @Test
    @DisplayName("finance's permission and the ledger's suspensions are kept; other events are ignored")
    void events() {
        this.listener.onEvent(envelope("foodstack.store.StorePaymentCollectionChanged.v1",
                new StorePaymentCollectionChanged("s1", "b1", "Napoli", java.util.Set.of("o1"), "DIRECT", 900L, "Signed agreement", "fin-1")));
        this.listener.onEvent(envelope("foodstack.ledger.StoreCreditLimitExceeded.v1",
                new StoreCreditLimitExceeded("s1", "b1", "Napoli", java.util.Set.of("o1"), 1_000, 900, "PKR")));
        this.listener.onEvent(envelope("foodstack.ledger.StoreCreditRestored.v1",
                new StoreCreditRestored("s1", "b1", "Napoli", java.util.Set.of("o1"), 0, 900, "PKR")));
        this.listener.onEvent(envelope("foodstack.store.StoreChanged.v1", Map.of("storeId", "s1")));

        verify(this.accounts).collectionChanged("s1", "b1", "DIRECT");
        verify(this.accounts).suspended("s1", "b1", true);
        verify(this.accounts).suspended("s1", "b1", false);
        verifyNoMoreInteractions(this.accounts);
    }

    private String envelope(String type, Object data) {
        return this.json.writeValueAsString(Map.of("specversion", "1.0", "id", "e1", "source", "foodstack/x", "type", type,
                "subject", "s1", "time", "2026-11-07T12:00:00Z", "data", data));
    }
}
