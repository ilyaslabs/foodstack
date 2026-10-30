package io.github.ilyaslabs.foodstack.identity.api.events;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import io.github.ilyaslabs.foodstack.identity.api.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract snapshots: removing or renaming a field breaks consumers and fails here. */
class IdentityEventsTest {

    private static final String USER = "0199c0de-0000-7000-8000-000000000001";

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    @DisplayName("UserRegistered matches its v1 snapshot")
    void userRegistered() throws IOException {
        assertSnapshot(new UserRegistered(USER, Set.of(Role.CUSTOMER), USER), "/events/UserRegistered.v1.json",
                UserRegistered.class);
    }

    @Test
    @DisplayName("UserTokensRevoked matches its v1 snapshot")
    void userTokensRevoked() throws IOException {
        assertSnapshot(new UserTokensRevoked(USER, 2, "LOGOUT_EVERYWHERE"), "/events/UserTokensRevoked.v1.json",
                UserTokensRevoked.class);
    }

    private <T> void assertSnapshot(T event, String path, Class<T> type) throws IOException {
        String snapshot;
        try (InputStream in = IdentityEventsTest.class.getResourceAsStream(path)) {
            snapshot = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(this.jsonMapper.readTree(this.jsonMapper.writeValueAsString(event))).isEqualTo(this.jsonMapper.readTree(snapshot));
        assertThat(this.jsonMapper.readValue(snapshot, type)).isEqualTo(event);
    }
}
