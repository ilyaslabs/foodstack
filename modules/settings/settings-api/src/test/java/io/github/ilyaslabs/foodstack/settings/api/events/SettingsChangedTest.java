package io.github.ilyaslabs.foodstack.settings.api.events;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract snapshot: removing or renaming a field breaks consumers and fails here. */
class SettingsChangedTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    @DisplayName("serializes to the committed v1 snapshot and reads it back")
    void snapshot() throws IOException {
        String snapshot = read("/events/SettingsChanged.v1.json");
        SettingsChanged event = new SettingsChanged("branding", 3, "user-1");

        assertThat(this.jsonMapper.readTree(this.jsonMapper.writeValueAsString(event)))
            .isEqualTo(this.jsonMapper.readTree(snapshot));
        assertThat(this.jsonMapper.readValue(snapshot, SettingsChanged.class)).isEqualTo(event);
        assertThat(event.subject()).isEqualTo("branding");
    }

    private static String read(String path) throws IOException {
        try (InputStream in = SettingsChangedTest.class.getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
