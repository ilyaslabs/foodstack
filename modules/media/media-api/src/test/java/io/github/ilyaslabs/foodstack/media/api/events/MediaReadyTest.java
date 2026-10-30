package io.github.ilyaslabs.foodstack.media.api.events;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import io.github.ilyaslabs.foodstack.media.api.MediaPurpose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract snapshot: removing or renaming a field breaks consumers and fails here. */
class MediaReadyTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    @DisplayName("serializes to the committed v1 snapshot and reads it back")
    void snapshot() throws IOException {
        String snapshot;
        try (InputStream in = MediaReadyTest.class.getResourceAsStream("/events/MediaReady.v1.json")) {
            snapshot = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        MediaReady event = new MediaReady("0199c0de-0000-7000-8000-000000000001", MediaPurpose.BRANDING,
                List.of("thumb", "card", "detail"), "user-1");

        assertThat(this.jsonMapper.readTree(this.jsonMapper.writeValueAsString(event)))
            .isEqualTo(this.jsonMapper.readTree(snapshot));
        assertThat(this.jsonMapper.readValue(snapshot, MediaReady.class)).isEqualTo(event);
    }
}
