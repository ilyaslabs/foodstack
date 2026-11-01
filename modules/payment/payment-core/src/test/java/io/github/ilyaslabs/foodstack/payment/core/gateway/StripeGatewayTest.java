package io.github.ilyaslabs.foodstack.payment.core.gateway;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import io.github.ilyaslabs.foodstack.payment.core.gateway.CardGateway.GatewayException;
import io.github.ilyaslabs.foodstack.payment.core.gateway.CardGateway.SessionRequest;
import io.github.ilyaslabs.foodstack.payment.core.gateway.CardGateway.WebhookEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The Stripe Checkout adapter against a mocked Stripe API (wire format and webhook signatures). */
class StripeGatewayTest {

    private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");

    private static final String WEBHOOK_SECRET = "whsec_test";

    private final RestClient.Builder builder = RestClient.builder();

    private final MockRestServiceServer server = MockRestServiceServer.bindTo(this.builder).build();

    private final StripeGateway gateway = new StripeGateway(this.builder, "https://api.stripe.test", "sk_test", WEBHOOK_SECRET,
            JsonMapper.builder().build(), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("opens a Checkout Session for the order, at least 30 minutes long, with an idempotency key")
    void session() {
        this.server.expect(requestTo("https://api.stripe.test/v1/checkout/sessions"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer sk_test"))
            .andExpect(header("Idempotency-Key", "checkout-o1"))
            .andExpect(content().formDataContains(Map.of("mode", "payment", "client_reference_id", "o1",
                    "line_items[0][price_data][currency]", "pkr", "line_items[0][price_data][unit_amount]", "125000",
                    "expires_at", String.valueOf(NOW.plusSeconds(31 * 60).getEpochSecond()), "success_url",
                    "https://shop/return")))
            .andRespond(withSuccess("""
                    {"id":"cs_1","url":"https://checkout.stripe.test/cs_1","expires_at":%d}"""
                .formatted(NOW.plusSeconds(1860).getEpochSecond()), MediaType.APPLICATION_JSON));

        CardGateway.HostedSession session = this.gateway.createSession(new SessionRequest("o1", "A-1", 125_000, "PKR",
                "Spice Route", "https://shop/return", "https://shop/cancel", NOW.plusSeconds(600)));

        assertThat(session.sessionReference()).isEqualTo("cs_1");
        assertThat(session.redirectUrl()).isEqualTo("https://checkout.stripe.test/cs_1");
        assertThat(session.expiresAt()).isEqualTo(NOW.plusSeconds(1860));
        this.server.verify();
    }

    @Test
    @DisplayName("Stripe errors: a refused request is final, an outage may be retried")
    void errors() {
        this.server.expect(requestTo("https://api.stripe.test/v1/refunds"))
            .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"message\":\"Charge already refunded\"}}"));
        assertThatThrownBy(() -> this.gateway.refund("pi_1", 500, "PKR", "r1")).isInstanceOfSatisfying(GatewayException.class,
                (ex) -> {
                    assertThat(ex.isRetryable()).isFalse();
                    assertThat(ex.getMessage()).contains("Charge already refunded");
                });

        this.server.reset();
        this.server.expect(requestTo("https://api.stripe.test/v1/refunds"))
            .andExpect(header("Idempotency-Key", "refund-r2"))
            .andExpect(content().formDataContains(Map.of("payment_intent", "pi_1", "amount", "500")))
            .andRespond(withServerError());
        assertThatThrownBy(() -> this.gateway.refund("pi_1", 500, "PKR", "r2"))
            .isInstanceOfSatisfying(GatewayException.class, (ex) -> assertThat(ex.isRetryable()).isTrue());

        this.server.reset();
        this.server.expect(requestTo("https://api.stripe.test/v1/refunds"))
            .andRespond(withSuccess("{\"id\":\"re_1\"}", MediaType.APPLICATION_JSON));
        assertThat(this.gateway.refund("pi_1", 500, "PKR", "r3")).isEqualTo("re_1");
    }

    @Test
    @DisplayName("reads signed Checkout webhooks: paid, failed later and expired; ignores the rest")
    void webhooks() {
        WebhookEvent paid = this.gateway.parseWebhook(body("checkout.session.completed", "paid"), signed(NOW, "checkout.session.completed", "paid"));
        assertThat(paid.outcome()).isEqualTo(WebhookEvent.SUCCEEDED);
        assertThat(paid.orderId()).isEqualTo("o1");
        assertThat(paid.sessionReference()).isEqualTo("cs_1");
        assertThat(paid.paymentReference()).isEqualTo("pi_1");
        assertThat(paid.amountMinor()).isEqualTo(125_000);

        assertThat(this.gateway.parseWebhook(body("checkout.session.completed", "unpaid"),
                signed(NOW, "checkout.session.completed", "unpaid"))).isNull();
        assertThat(this.gateway.parseWebhook(body("checkout.session.async_payment_failed", "unpaid"),
                signed(NOW, "checkout.session.async_payment_failed", "unpaid")).outcome()).isEqualTo(WebhookEvent.FAILED);
        assertThat(this.gateway.parseWebhook(body("checkout.session.expired", "unpaid"),
                signed(NOW, "checkout.session.expired", "unpaid")).outcome()).isEqualTo(WebhookEvent.EXPIRED);
        assertThat(this.gateway.parseWebhook(body("customer.created", "paid"), signed(NOW, "customer.created", "paid"))).isNull();
    }

    @Test
    @DisplayName("refuses webhooks that are unsigned, wrongly signed, changed or replayed later")
    void signatures() {
        byte[] body = body("checkout.session.completed", "paid");
        assertThatThrownBy(() -> this.gateway.parseWebhook(body, Map.of())).isInstanceOf(GatewayException.class);
        assertThatThrownBy(() -> this.gateway.parseWebhook(body, Map.of("stripe-signature", "garbage")))
            .isInstanceOf(GatewayException.class);
        assertThatThrownBy(() -> this.gateway.parseWebhook(body("checkout.session.completed", "paid ").clone(),
                signed(NOW, "checkout.session.completed", "unpaid"))).isInstanceOf(GatewayException.class);
        assertThatThrownBy(() -> this.gateway.parseWebhook(body, signed(NOW.minusSeconds(301), "checkout.session.completed", "paid")))
            .isInstanceOf(GatewayException.class);
        String header = "t=" + NOW.getEpochSecond() + ",v1=" + "0".repeat(64) + ",v1="
                + hmac(NOW.getEpochSecond() + "." + new String(body, StandardCharsets.UTF_8));
        assertThat(this.gateway.parseWebhook(body, Map.of("stripe-signature", header))).isNotNull();
    }

    private static byte[] body(String type, String paymentStatus) {
        return """
                {"id":"evt_1","type":"%s","data":{"object":{"id":"cs_1","client_reference_id":"o1",
                "payment_intent":"pi_1","payment_status":"%s","amount_total":125000}}}"""
            .formatted(type, paymentStatus).getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, String> signed(Instant at, String type, String paymentStatus) {
        String payload = at.getEpochSecond() + "." + new String(body(type, paymentStatus), StandardCharsets.UTF_8);
        return Map.of("stripe-signature", "t=" + at.getEpochSecond() + ",v1=" + hmac(payload));
    }

    private static String hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        }
        catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
