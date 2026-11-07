package io.github.ilyaslabs.foodstack.payment.core.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stripe Checkout (TDD 5.4, the first card gateway): a Checkout Session per order, either on
 * Stripe's hosted page or embedded in the app's checkout ({@code ui_mode=embedded}, needs the
 * publishable key), webhooks verified with the endpoint's signing secret, refunds against the
 * payment intent. Saved cards (PRD CO-5a) are Stripe customers: Checkout offers the customer's
 * cards and asks before saving a new one; FoodStack keeps only the {@code cus_...} reference.
 * Amounts are passed in the currency's minor units, which is what Stripe expects for the common
 * two-decimal currencies; zero-decimal currencies need no conversion either.
 */
public class StripeGateway implements CardGateway {

    public static final String ID = "stripe";

    static final Duration SIGNATURE_TOLERANCE = Duration.ofMinutes(5);

    /** Stripe refuses Checkout Sessions that expire sooner than this. */
    static final Duration MIN_SESSION = Duration.ofMinutes(30);

    private final RestClient http;

    private final byte[] webhookSecret;

    private final JsonMapper jsonMapper;

    private final Clock clock;

    private final String publishableKey;

    public StripeGateway(RestClient.Builder builder, String apiUrl, String secretKey, String webhookSecret, JsonMapper jsonMapper,
            Clock clock) {
        this(builder, apiUrl, secretKey, webhookSecret, null, jsonMapper, clock);
    }

    /** @param publishableKey {@code pk_...}, needed for embedded fields; {@code null} for hosted pages only */
    public StripeGateway(RestClient.Builder builder, String apiUrl, String secretKey, String webhookSecret,
            String publishableKey, JsonMapper jsonMapper, Clock clock) {
        this.http = builder.clone().baseUrl(apiUrl).defaultHeaders((h) -> h.setBearerAuth(secretKey)).build();
        this.webhookSecret = webhookSecret.getBytes(StandardCharsets.UTF_8);
        this.publishableKey = (publishableKey != null && !publishableKey.isBlank()) ? publishableKey : null;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @Override
    public boolean supportsEmbedded() {
        return this.publishableKey != null;
    }

    @Override
    public String publishableKey() {
        return this.publishableKey;
    }

    @Override
    public boolean supportsSavedCards() {
        return true;
    }

    @Override
    public String createCustomer(String userId) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("metadata[userId]", userId);
        return post("/v1/customers", form, "customer-" + userId).path("id").asString();
    }

    @Override
    public java.util.List<SavedCard> cards(String customerReference) {
        JsonNode list = get("/v1/customers/" + customerReference + "/payment_methods?type=card&limit=20");
        java.util.List<SavedCard> cards = new java.util.ArrayList<>();
        for (JsonNode method : list.path("data")) {
            JsonNode card = method.path("card");
            cards.add(new SavedCard(method.path("id").asString(), card.path("brand").asString(), card.path("last4").asString(),
                    card.path("exp_month").asInt(), card.path("exp_year").asInt()));
        }
        return cards;
    }

    @Override
    public void removeCard(String customerReference, String cardId) {
        post("/v1/payment_methods/" + cardId + "/detach", new LinkedMultiValueMap<>(), "detach-" + cardId);
    }

    @Override
    public void deleteCustomer(String customerReference) {
        try {
            this.http.delete().uri("/v1/customers/" + customerReference).retrieve().body(String.class);
        }
        catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() != 404) {
                throw failure(ex);
            }
        }
        catch (ResourceAccessException ex) {
            throw new GatewayException("Stripe cannot be reached", true, ex);
        }
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public HostedSession createSession(SessionRequest request) {
        Instant earliest = this.clock.instant().plus(MIN_SESSION).plusSeconds(60);
        Instant expiresAt = request.expiresAt().isBefore(earliest) ? earliest : request.expiresAt();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("mode", "payment");
        if (request.embedded()) {
            form.add("ui_mode", "embedded");
            form.add("return_url", request.returnUrl());
        }
        else {
            form.add("success_url", request.returnUrl());
            form.add("cancel_url", request.cancelUrl());
        }
        if (request.customerReference() != null) {
            form.add("customer", request.customerReference());
            form.add("saved_payment_method_options[payment_method_save]", "enabled");
        }
        form.add("client_reference_id", request.orderId());
        form.add("metadata[orderId]", request.orderId());
        form.add("payment_intent_data[metadata][orderId]", request.orderId());
        form.add("expires_at", String.valueOf(expiresAt.getEpochSecond()));
        form.add("line_items[0][quantity]", "1");
        form.add("line_items[0][price_data][currency]", request.currency().toLowerCase(Locale.ROOT));
        form.add("line_items[0][price_data][unit_amount]", String.valueOf(request.amountMinor()));
        form.add("line_items[0][price_data][product_data][name]", request.description() + " · " + request.orderNumber());
        JsonNode session = post("/v1/checkout/sessions", form, "checkout-" + request.orderId());
        return new HostedSession(session.path("id").asString(), session.path("url").asString(null),
                Instant.ofEpochSecond(session.path("expires_at").asLong(expiresAt.getEpochSecond())),
                session.path("client_secret").asString(null));
    }

    @Override
    public WebhookEvent parseWebhook(byte[] body, Map<String, String> headers) {
        verify(body, headers.get("stripe-signature"));
        JsonNode event = this.jsonMapper.readTree(body);
        JsonNode session = event.path("data").path("object");
        String type = event.path("type").asString();
        String outcome = switch (type) {
            case "checkout.session.completed" -> "paid".equals(session.path("payment_status").asString())
                    ? WebhookEvent.SUCCEEDED : null;
            case "checkout.session.async_payment_succeeded" -> WebhookEvent.SUCCEEDED;
            case "checkout.session.async_payment_failed" -> WebhookEvent.FAILED;
            case "checkout.session.expired" -> WebhookEvent.EXPIRED;
            default -> null;
        };
        if (outcome == null) {
            return null;
        }
        return new WebhookEvent(outcome, session.path("client_reference_id").asString(), session.path("id").asString(),
                session.path("payment_intent").asString(null), session.path("amount_total").asLong(),
                WebhookEvent.FAILED.equals(outcome) ? "DECLINED" : null);
    }

    /** Reads the account's balance: allowed for every working secret key, and changes nothing. */
    @Override
    public void verify() {
        try {
            this.http.get().uri("/v1/balance").retrieve().body(String.class);
        }
        catch (RestClientResponseException ex) {
            HttpStatusCode status = ex.getStatusCode();
            String message = status.toString();
            try {
                message = this.jsonMapper.readTree(ex.getResponseBodyAsString()).path("error").path("message").asString(message);
            }
            catch (RuntimeException unreadable) {
                // keep the status as the message
            }
            throw new GatewayException("Stripe: " + message, status.is5xxServerError() || status.value() == 429, ex);
        }
        catch (ResourceAccessException ex) {
            throw new GatewayException("Stripe cannot be reached", true, ex);
        }
    }

    @Override
    public String refund(String paymentReference, long amountMinor, String currency, String idempotencyKey) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("payment_intent", paymentReference);
        form.add("amount", String.valueOf(amountMinor));
        return post("/v1/refunds", form, "refund-" + idempotencyKey).path("id").asString();
    }

    void verify(byte[] body, String header) {
        if (header == null) {
            throw new GatewayException("Missing Stripe-Signature", false, null);
        }
        long timestamp = -1;
        java.util.List<String> signatures = new java.util.ArrayList<>();
        for (String part : header.split(",")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length == 2 && pair[0].equals("t")) {
                try {
                    timestamp = Long.parseLong(pair[1]);
                }
                catch (NumberFormatException ex) {
                    timestamp = -1;
                }
            }
            else if (pair.length == 2 && pair[0].equals("v1")) {
                signatures.add(pair[1]);
            }
        }
        if (timestamp < 0 || signatures.isEmpty()) {
            throw new GatewayException("Malformed Stripe-Signature", false, null);
        }
        if (Duration.between(Instant.ofEpochSecond(timestamp), this.clock.instant()).abs().compareTo(SIGNATURE_TOLERANCE) > 0) {
            throw new GatewayException("Stripe webhook is too old", false, null);
        }
        byte[] expected = hmac((timestamp + ".").getBytes(StandardCharsets.UTF_8), body).getBytes(StandardCharsets.UTF_8);
        if (signatures.stream().noneMatch((s) -> MessageDigest.isEqual(expected, s.getBytes(StandardCharsets.UTF_8)))) {
            throw new GatewayException("Stripe webhook signature does not match", false, null);
        }
    }

    private String hmac(byte[] prefix, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(this.webhookSecret, "HmacSHA256"));
            mac.update(prefix);
            return HexFormat.of().formatHex(mac.doFinal(body));
        }
        catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private JsonNode get(String path) {
        try {
            return this.jsonMapper.readTree(this.http.get().uri(path).retrieve().body(String.class));
        }
        catch (RestClientResponseException ex) {
            throw failure(ex);
        }
        catch (ResourceAccessException ex) {
            throw new GatewayException("Stripe cannot be reached", true, ex);
        }
    }

    private GatewayException failure(RestClientResponseException ex) {
        HttpStatusCode status = ex.getStatusCode();
        boolean retryable = status.is5xxServerError() || status.value() == 429 || status.value() == 409;
        String message = status.toString();
        try {
            message = this.jsonMapper.readTree(ex.getResponseBodyAsString()).path("error").path("message").asString(message);
        }
        catch (RuntimeException unreadable) {
            // keep the status as the message
        }
        return new GatewayException("Stripe: " + message, retryable, ex);
    }

    private JsonNode post(String path, MultiValueMap<String, String> form, String idempotencyKey) {
        try {
            String body = this.http.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header("Idempotency-Key", idempotencyKey)
                .body(form)
                .retrieve()
                .body(String.class);
            return this.jsonMapper.readTree(body);
        }
        catch (RestClientResponseException ex) {
            HttpStatusCode status = ex.getStatusCode();
            boolean retryable = status.is5xxServerError() || status.value() == 429 || status.value() == 409;
            String message = status.toString();
            try {
                message = this.jsonMapper.readTree(ex.getResponseBodyAsString()).path("error").path("message").asString(message);
            }
            catch (RuntimeException unreadable) {
                // keep the status as the message
            }
            throw new GatewayException("Stripe: " + message, retryable, ex);
        }
        catch (ResourceAccessException ex) {
            throw new GatewayException("Stripe cannot be reached", true, ex);
        }
    }
}
