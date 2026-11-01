package io.github.ilyaslabs.foodstack.payment.core.gateway;

import java.time.Instant;
import java.util.Map;

import io.github.ilyaslabs.foodstack.payment.core.gateway.CardGateway.GatewayException;
import io.github.ilyaslabs.foodstack.payment.core.gateway.CardGateway.SessionRequest;
import io.github.ilyaslabs.foodstack.payment.core.gateway.CardGateway.WebhookEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SandboxGatewayTest {

    private final SandboxGateway gateway = new SandboxGateway("http://localhost:8080/", "secret", JsonMapper.builder().build());

    @Test
    @DisplayName("opens a page on this server whose token proves the server handed it out")
    void page() {
        Instant expires = Instant.parse("2026-11-01T12:30:00Z");
        CardGateway.HostedSession session = this.gateway.createSession(
                new SessionRequest("o1", "A-1", 1_000, "PKR", "Spice Route", "http://r", "http://c", expires));

        assertThat(session.sessionReference()).startsWith("sbx_");
        assertThat(session.redirectUrl()).isEqualTo("http://localhost:8080/api/v1/payments/sandbox/o1?t=" + this.gateway.pageToken("o1"));
        assertThat(session.expiresAt()).isEqualTo(expires);
        assertThat(this.gateway.validPageToken("o1", this.gateway.pageToken("o1"))).isTrue();
        assertThat(this.gateway.validPageToken("o2", this.gateway.pageToken("o1"))).isFalse();
        assertThat(this.gateway.validPageToken("o1", null)).isFalse();
        assertThat(new SandboxGateway("http://x", "other", JsonMapper.builder().build()).validPageToken("o1",
                this.gateway.pageToken("o1"))).isFalse();
    }

    @Test
    @DisplayName("reads its own signed webhooks and refuses changed or unsigned ones")
    void webhooks() {
        Map.Entry<byte[], Map<String, String>> paid = this.gateway.webhook(WebhookEvent.SUCCEEDED, "o1", "sbx_1", 1_000);
        WebhookEvent event = this.gateway.parseWebhook(paid.getKey(), paid.getValue());
        assertThat(event.outcome()).isEqualTo(WebhookEvent.SUCCEEDED);
        assertThat(event.orderId()).isEqualTo("o1");
        assertThat(event.sessionReference()).isEqualTo("sbx_1");
        assertThat(event.amountMinor()).isEqualTo(1_000);

        Map.Entry<byte[], Map<String, String>> declined = this.gateway.webhook(WebhookEvent.FAILED, "o1", "sbx_1", 1_000);
        assertThat(this.gateway.parseWebhook(declined.getKey(), declined.getValue()).reason()).isEqualTo("DECLINED");

        byte[] changed = new String(paid.getKey()).replace("1000", "1").getBytes();
        assertThatThrownBy(() -> this.gateway.parseWebhook(changed, paid.getValue())).isInstanceOf(GatewayException.class);
        assertThatThrownBy(() -> this.gateway.parseWebhook(paid.getKey(), Map.of())).isInstanceOf(GatewayException.class);
    }
}
