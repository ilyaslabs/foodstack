package io.github.ilyaslabs.foodstack.store.api.events;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract snapshots: removing or renaming a field breaks consumers and fails here. */
class StoreEventsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    static java.util.stream.Stream<Arguments> events() {
        return java.util.stream.Stream.of(
                Arguments.of("BrandChanged", new BrandChanged("brand-1", 2, "user-1"), "brand-1"),
                Arguments.of("StoreChanged", new StoreChanged("store-1", "brand-1", 3, "user-1"), "store-1"),
                Arguments.of("MenuChanged", new MenuChanged("brand-1", "user-1"), "brand-1"),
                Arguments.of("ItemAvailabilityChanged", new ItemAvailabilityChanged("store-1", "item-1", null, true, "user-1"),
                        "store-1"),
                Arguments.of("StoreAvailabilityChanged", new StoreAvailabilityChanged("store-1", true, "Power cut", "user-1"),
                        "store-1"));
    }

    @ParameterizedTest(name = "{0} matches its committed v1 snapshot")
    @MethodSource("events")
    void snapshot(String name, ModuleEvent event, String subject) throws IOException {
        String snapshot = read("/events/" + name + ".v1.json");

        assertThat(JSON.readTree(JSON.writeValueAsString(event))).isEqualTo(JSON.readTree(snapshot));
        assertThat(JSON.readValue(snapshot, event.getClass())).isEqualTo(event);
        assertThat(event.subject()).isEqualTo(subject);
    }

    private static String read(String path) throws IOException {
        try (InputStream in = StoreEventsTest.class.getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
