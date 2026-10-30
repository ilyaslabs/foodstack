package io.github.ilyaslabs.foodstack.platform.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class EventTypesTest {

    record SomethingHappened(String subject) implements ModuleEvent {
    }

    @EventVersion(2)
    record SomethingChanged(String subject) implements ModuleEvent {
    }

    @Test
    @DisplayName("names topics, sources and versioned types after the module")
    void names() {
        assertThat(EventTypes.topic("order")).isEqualTo("foodstack.order.events");
        assertThat(EventTypes.deadLetterTopic("order")).isEqualTo("foodstack.order.events.dlt");
        assertThat(EventTypes.source("order")).isEqualTo("foodstack/order");
        assertThat(EventTypes.type("order", SomethingHappened.class)).isEqualTo("foodstack.order.SomethingHappened.v1");
        assertThat(EventTypes.type("order", SomethingChanged.class)).isEqualTo("foodstack.order.SomethingChanged.v2");
    }

    @Test
    @DisplayName("rejects module names that are not lower-case words")
    void invalidModule() {
        assertThatIllegalArgumentException().isThrownBy(() -> EventTypes.topic("Order"));
        assertThatIllegalArgumentException().isThrownBy(() -> EventTypes.topic("order.x"));
    }
}
