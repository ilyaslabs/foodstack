package io.github.ilyaslabs.foodstack.notification.core.push;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** FCM HTTP v1 against a stand-in server: the signed sign-in, the message and the error answers. */
class FcmPushTransportTest {

    private static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z");

    private final JsonMapper json = JsonMapper.builder().build();

    private KeyPair keys;

    private MockRestServiceServer server;

    private FcmPushTransport transport;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        this.keys = generator.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(this.keys.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----\n";
        String account = this.json.writeValueAsString(Map.of("type", "service_account", "project_id", "eats-demo",
                "client_email", "push@eats-demo.iam.example", "private_key", pem, "token_uri", "https://oauth.test/token"));
        RestClient.Builder builder = RestClient.builder();
        this.server = MockRestServiceServer.bindTo(builder).build();
        this.transport = new FcmPushTransport(builder, this.json, account, "https://fcm.test/", Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("signs in once with a signed assertion and sends the message with its link and data")
    void sends() {
        this.server.expect(once(), requestTo("https://oauth.test/token"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer")))
            .andRespond(withSuccess("{\"access_token\":\"at-1\",\"expires_in\":3600}", MediaType.APPLICATION_JSON));
        for (int i = 0; i < 2; i++) {
            this.server.expect(requestTo("https://fcm.test/v1/projects/eats-demo/messages:send"))
                .andExpect(header("Authorization", "Bearer at-1"))
                .andExpect(jsonPath("$.message.token").value("tok-1"))
                .andExpect(jsonPath("$.message.notification.title").value("New delivery"))
                .andExpect(jsonPath("$.message.webpush.fcm_options.link").value("https://eats.example/admin/rider"))
                .andExpect(jsonPath("$.message.data.type").value("rider-offer"))
                .andRespond(withSuccess("{\"name\":\"projects/eats-demo/messages/1\"}", MediaType.APPLICATION_JSON));
        }

        for (int i = 0; i < 2; i++) {
            assertThat(this.transport.send("tok-1", "New delivery", "Order A-1", "https://eats.example/admin/rider",
                    Map.of("type", "rider-offer"))).isEqualTo(PushTransport.Result.SENT);
        }
        this.server.verify();
    }

    @Test
    @DisplayName("the assertion is an RS256 token for the service account, valid one hour")
    void assertion() throws Exception {
        String[] parts = this.transport.assertion(NOW).split("\\.");
        Base64.Decoder url = Base64.getUrlDecoder();
        assertThat(new String(url.decode(parts[0]), StandardCharsets.UTF_8)).contains("RS256");
        Map<?, ?> claims = this.json.readValue(url.decode(parts[1]), Map.class);
        assertThat(claims.get("iss")).isEqualTo("push@eats-demo.iam.example");
        assertThat(claims.get("aud")).isEqualTo("https://oauth.test/token");
        assertThat(((Number) claims.get("exp")).longValue() - ((Number) claims.get("iat")).longValue()).isEqualTo(3600);
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(this.keys.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(url.decode(parts[2]))).isTrue();
    }

    @Test
    @DisplayName("an unregistered token is reported so it can be forgotten; other errors are failures")
    void errors() {
        this.server.expect(requestTo("https://oauth.test/token"))
            .andRespond(withSuccess("{\"access_token\":\"at-1\",\"expires_in\":3600}", MediaType.APPLICATION_JSON));
        this.server.expect(requestTo("https://fcm.test/v1/projects/eats-demo/messages:send"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"status\":\"NOT_FOUND\",\"details\":[{\"errorCode\":\"UNREGISTERED\"}]}}"));
        this.server.expect(requestTo("https://fcm.test/v1/projects/eats-demo/messages:send"))
            .andRespond(withStatus(HttpStatus.FORBIDDEN).body("{\"error\":{\"status\":\"PERMISSION_DENIED\"}}"));
        this.server.expect(requestTo("https://fcm.test/v1/projects/eats-demo/messages:send"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(this.transport.send("gone", "t", "b", null, Map.of())).isEqualTo(PushTransport.Result.INVALID_TOKEN);
        assertThat(this.transport.send("tok", "t", "b", null, Map.of())).isEqualTo(PushTransport.Result.FAILED);
        assertThat(this.transport.send("tok", "t", "b", null, Map.of())).isEqualTo(PushTransport.Result.FAILED);
        this.server.verify();
    }
}
