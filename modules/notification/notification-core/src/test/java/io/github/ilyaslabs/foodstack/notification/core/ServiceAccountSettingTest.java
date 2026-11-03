package io.github.ilyaslabs.foodstack.notification.core;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServiceAccountSettingTest {

    @Test
    @DisplayName("the FCM service account setting takes the key's JSON itself")
    void inlineJson() {
        assertThat(NotificationConfiguration.serviceAccountJson("  {\"project_id\":\"demo\"} ")).isEqualTo("{\"project_id\":\"demo\"}");
    }

    @Test
    @DisplayName("or the path of the key file, which is read")
    void filePath(@TempDir Path dir) throws Exception {
        Path key = Files.writeString(dir.resolve("fcm.json"), "{\n  \"project_id\": \"demo\"\n}\n");
        assertThat(NotificationConfiguration.serviceAccountJson(key.toString())).contains("\"project_id\": \"demo\"");
    }

    @Test
    @DisplayName("a missing key file stops start-up with the path in the message")
    void missingFile(@TempDir Path dir) {
        assertThatThrownBy(() -> NotificationConfiguration.serviceAccountJson(dir.resolve("nope.json").toString()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("nope.json");
    }
}
