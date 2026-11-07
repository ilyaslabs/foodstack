package io.github.ilyaslabs.foodstack.identity.core.events;

import java.time.Instant;
import java.util.List;

import io.github.ilyaslabs.foodstack.identity.core.application.AuditLog;
import io.github.ilyaslabs.foodstack.platform.core.AuditEntry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AuditListenerTest {

    @Test
    @DisplayName("keeps each entry a module sends to the audit topic")
    void keeps() {
        JsonMapper json = JsonMapper.builder().build();
        AuditLog log = mock(AuditLog.class);
        AuditEntry entry = new AuditEntry("e1", Instant.parse("2026-11-06T12:00:00Z"), "admin-1", null, List.of("ADMIN"),
                "stores", "PUT /api/v1/stores/{storeId}", "/api/v1/stores/s1", 200, "{}", "{}", "10.0.0.1", null);

        new AuditListener(log, json).onEntry(json.writeValueAsString(entry));

        verify(log).record(entry);
    }
}
