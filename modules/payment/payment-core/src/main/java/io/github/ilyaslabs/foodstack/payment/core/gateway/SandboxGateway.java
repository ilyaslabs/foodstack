package io.github.ilyaslabs.foodstack.payment.core.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import io.github.ilyaslabs.foodstack.platform.core.Ids;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A pretend gateway for development, tests and demos: its "hosted page" is served by the payment
 * module itself ({@code /api/v1/payments/sandbox/{orderId}}) with Pay and Decline buttons, and the
 * outcome travels through the same signed-webhook path a real gateway uses. In the embedded style
 * the app shows pretend card fields itself and sends the outcome the same way. Saved cards are kept
 * in {@link SandboxCards} when it is given. Never enabled in production.
 */
public class SandboxGateway implements CardGateway {

    public static final String ID = "sandbox";

    static final String SIGNATURE_HEADER = "sandbox-signature";

    private final String publicUrl;

    private final byte[] secret;

    private final JsonMapper jsonMapper;

    /** A store account's key, or {@code null} for the operator's own sandbox. */
    private final String accountKey;

    /** Saved cards, or {@code null} when the sandbox keeps none. */
    private final SandboxCards cards;

    public SandboxGateway(String publicUrl, String secret, JsonMapper jsonMapper) {
        this(publicUrl, secret, jsonMapper, null);
    }

    public SandboxGateway(String publicUrl, String secret, JsonMapper jsonMapper, SandboxCards cards) {
        this(publicUrl, secret.getBytes(StandardCharsets.UTF_8), jsonMapper, null, cards);
    }

    private SandboxGateway(String publicUrl, byte[] secret, JsonMapper jsonMapper, String accountKey, SandboxCards cards) {
        this.publicUrl = publicUrl.replaceAll("/+$", "");
        this.secret = secret;
        this.jsonMapper = jsonMapper;
        this.accountKey = accountKey;
        this.cards = cards;
    }

    @Override
    public boolean supportsEmbedded() {
        return true;
    }

    @Override
    public boolean supportsSavedCards() {
        return this.cards != null && this.accountKey == null;
    }

    @Override
    public String createCustomer(String userId) {
        return "sbx_cus_" + sign(("customer:" + userId).getBytes(StandardCharsets.UTF_8)).substring(0, 24);
    }

    @Override
    public java.util.List<SavedCard> cards(String customerReference) {
        return (this.cards != null) ? this.cards.list(customerReference) : java.util.List.of();
    }

    @Override
    public void removeCard(String customerReference, String cardId) {
        if (this.cards != null) {
            this.cards.remove(customerReference, cardId);
        }
    }

    @Override
    public void deleteCustomer(String customerReference) {
        if (this.cards != null) {
            this.cards.removeAll(customerReference);
        }
    }

    /** Keeps the card a customer paid with, when they asked to save it. */
    public SavedCard saveCard(String customerReference, String last4) {
        if (this.cards == null || customerReference == null) {
            return null;
        }
        return this.cards.save(customerReference, last4);
    }

    /**
     * The sandbox on a store's own account. Its pages and webhooks are signed like the operator's,
     * so the same page serves both; only {@link #verify()} looks at the key: keys starting with
     * {@code sbx_} work, anything else is refused, so a failed connection can be tried out.
     */
    public SandboxGateway forAccount(String secretKey) {
        return new SandboxGateway(this.publicUrl, this.secret, this.jsonMapper, (secretKey != null) ? secretKey : "", this.cards);
    }

    @Override
    public void verify() {
        if (this.accountKey != null && !this.accountKey.startsWith("sbx_")) {
            throw new GatewayException("Sandbox: this key is not valid (sandbox keys start with sbx_)", false, null);
        }
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public HostedSession createSession(SessionRequest request) {
        String session = "sbx_" + Ids.newId();
        if (request.embedded()) {
            return new HostedSession(session, null, request.expiresAt(), session + "_secret_" + pageToken(request.orderId()));
        }
        String url = this.publicUrl + "/api/v1/payments/sandbox/" + request.orderId() + "?t=" + pageToken(request.orderId());
        return new HostedSession(session, url, request.expiresAt());
    }

    @Override
    public WebhookEvent parseWebhook(byte[] body, Map<String, String> headers) {
        String signature = headers.get(SIGNATURE_HEADER);
        if (signature == null || !MessageDigest.isEqual(sign(body).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8))) {
            throw new GatewayException("Sandbox webhook signature does not match", false, null);
        }
        JsonNode node = this.jsonMapper.readTree(body);
        return new WebhookEvent(node.path("outcome").asString(), node.path("orderId").asString(),
                node.path("session").asString(), node.path("session").asString(), node.path("amountMinor").asLong(),
                node.path("reason").isMissingNode() ? null : node.path("reason").asString(null));
    }

    @Override
    public String refund(String paymentReference, long amountMinor, String currency, String idempotencyKey) {
        return "sbx_re_" + idempotencyKey;
    }

    /** The body and signature header of the webhook the sandbox page sends for an outcome. */
    public Map.Entry<byte[], Map<String, String>> webhook(String outcome, String orderId, String session, long amountMinor) {
        byte[] body = this.jsonMapper.writeValueAsBytes(Map.of("outcome", outcome, "orderId", orderId, "session", session,
                "amountMinor", amountMinor, "reason", WebhookEvent.SUCCEEDED.equals(outcome) ? "" : "DECLINED"));
        return Map.entry(body, Map.of(SIGNATURE_HEADER, sign(body)));
    }

    /** Proof that a page URL was handed out by this server, so order ids cannot simply be guessed. */
    public String pageToken(String orderId) {
        return sign(("page:" + orderId).getBytes(StandardCharsets.UTF_8)).substring(0, 32);
    }

    public boolean validPageToken(String orderId, String token) {
        return token != null && MessageDigest.isEqual(pageToken(orderId).getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }

    private String sign(byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(this.secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data));
        }
        catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
