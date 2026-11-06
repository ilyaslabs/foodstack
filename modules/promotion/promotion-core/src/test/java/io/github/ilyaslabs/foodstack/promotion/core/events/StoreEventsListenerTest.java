package io.github.ilyaslabs.foodstack.promotion.core.events;

import java.util.Map;

import io.github.ilyaslabs.foodstack.promotion.core.application.BrandRules;
import io.github.ilyaslabs.foodstack.store.api.events.BranchPromotionsChanged;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class StoreEventsListenerTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    @DisplayName("keeps the brand's branch-promotions switch at the brand's version; other store events are ignored")
    void branchPromotions() {
        BrandRules rules = mock(BrandRules.class);
        new StoreEventsListener(rules, this.json).onEvent(envelope("foodstack.store.BranchPromotionsChanged.v1",
                new BranchPromotionsChanged("b1", true, 7, "owner-1")));
        verify(rules).apply("b1", true, 7);

        BrandRules other = mock(BrandRules.class);
        new StoreEventsListener(other, this.json).onEvent(envelope("foodstack.store.BrandChanged.v1", Map.of("brandId", "b1")));
        verifyNoInteractions(other);
    }

    private String envelope(String type, Object data) {
        return this.json.writeValueAsString(Map.of("specversion", "1.0", "id", "e1", "source", "foodstack/store", "type", type,
                "subject", "b1", "time", "2026-11-06T12:00:00Z", "data", data));
    }
}
