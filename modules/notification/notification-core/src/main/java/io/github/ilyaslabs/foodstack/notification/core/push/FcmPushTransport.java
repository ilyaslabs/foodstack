package io.github.ilyaslabs.foodstack.notification.core.push;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Firebase Cloud Messaging HTTP v1 (TDD 8.3): signs a short-lived assertion with the operator's
 * service account (RS256), trades it for an access token (cached until shortly before it expires) and
 * sends one message per device. Web, Android and iOS devices all use this one API.
 */
public class FcmPushTransport implements PushTransport {

    private static final Logger log = LoggerFactory.getLogger(FcmPushTransport.class);

    static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

    private final RestClient client;

    private final JsonMapper json;

    private final String fcmUrl;

    private final String projectId;

    private final String clientEmail;

    private final String tokenUri;

    private final PrivateKey key;

    private final Clock clock;

    private String accessToken;

    private Instant accessTokenExpiresAt = Instant.EPOCH;

    /**
     * @param serviceAccountJson the service account key file Firebase gives, as JSON text
     * @param fcmUrl {@code https://fcm.googleapis.com}, or a stand-in for tests
     */
    public FcmPushTransport(RestClient.Builder builder, JsonMapper json, String serviceAccountJson, String fcmUrl, Clock clock) {
        this.client = builder.build();
        this.json = json;
        this.fcmUrl = fcmUrl.replaceAll("/+$", "");
        this.clock = clock;
        JsonNode account = json.readTree(serviceAccountJson);
        this.projectId = account.path("project_id").asString();
        this.clientEmail = account.path("client_email").asString();
        this.tokenUri = account.path("token_uri").asString("https://oauth2.googleapis.com/token");
        this.key = privateKey(account.path("private_key").asString());
        if (this.projectId.isBlank() || this.clientEmail.isBlank()) {
            throw new IllegalArgumentException("The FCM service account needs project_id and client_email");
        }
    }

    @Override
    public Result send(String token, String title, String body, String link, Map<String, String> data) {
        Map<String, Object> notification = Map.of("title", title, "body", body);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("token", token);
        message.put("notification", notification);
        if (data != null && !data.isEmpty()) {
            message.put("data", data);
        }
        message.put("android", Map.of("priority", "high"));
        if (link != null) {
            message.put("webpush", Map.of("fcm_options", Map.of("link", link)));
        }
        try {
            this.client.post()
                .uri(this.fcmUrl + "/v1/projects/" + this.projectId + "/messages:send")
                .header("Authorization", "Bearer " + accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(this.json.writeValueAsString(Map.of("message", message)))
                .retrieve()
                .toBodilessEntity();
            return Result.SENT;
        }
        catch (HttpClientErrorException ex) {
            String answer = ex.getResponseBodyAsString();
            if (ex.getStatusCode().value() == 404 || answer.contains("UNREGISTERED")
                    || (ex.getStatusCode().value() == 400 && answer.contains("registration token"))) {
                return Result.INVALID_TOKEN;
            }
            if (ex.getStatusCode().value() == 401) {
                this.accessTokenExpiresAt = Instant.EPOCH;
            }
            log.warn("Push not sent: {} {}", ex.getStatusCode(), answer.length() > 300 ? answer.substring(0, 300) : answer);
            return Result.FAILED;
        }
        catch (RestClientException ex) {
            log.warn("Push not sent: {}", ex.getMessage());
            return Result.FAILED;
        }
    }

    synchronized String accessToken() {
        Instant now = this.clock.instant();
        if (this.accessToken != null && now.isBefore(this.accessTokenExpiresAt.minusSeconds(120))) {
            return this.accessToken;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
        form.add("assertion", assertion(now));
        JsonNode answer = this.client.post()
            .uri(this.tokenUri)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(JsonNode.class);
        this.accessToken = answer.path("access_token").asString();
        this.accessTokenExpiresAt = now.plusSeconds(answer.path("expires_in").asLong(3600));
        return this.accessToken;
    }

    String assertion(Instant now) {
        Base64.Encoder url = Base64.getUrlEncoder().withoutPadding();
        String header = url.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        Map<String, Object> claims = Map.of("iss", this.clientEmail, "scope", SCOPE, "aud", this.tokenUri, "iat",
                now.getEpochSecond(), "exp", now.getEpochSecond() + 3600);
        String payload = url.encodeToString(this.json.writeValueAsString(claims).getBytes(StandardCharsets.UTF_8));
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(this.key);
            signature.update((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
            return header + "." + payload + "." + url.encodeToString(signature.sign());
        }
        catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("Could not sign the FCM assertion", ex);
        }
    }

    private static PrivateKey privateKey(String pem) {
        String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        }
        catch (java.security.GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalArgumentException("The FCM service account private_key is not a PKCS#8 RSA key", ex);
        }
    }
}
